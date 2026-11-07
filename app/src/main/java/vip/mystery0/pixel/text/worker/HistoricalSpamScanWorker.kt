package vip.mystery0.pixel.text.worker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.repository.MirrorSpamScanner
import vip.mystery0.pixel.text.data.repository.StaleSpamScanSessionException
import vip.mystery0.pixel.text.notification.SpamScanNotificationHelper
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration
import java.util.UUID

class HistoricalSpamScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    companion object {
        private const val TAG = "HistoricalSpamScanWorker"
        private const val BATCH_SIZE = 200
        private const val INFERENCE_PARALLELISM = 2
        const val UNIQUE_WORK_NAME = "historical_spam_scan"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_SPAM_COUNT = "spam_count"
        private const val KEY_SILENT = "silent"
        private const val KEY_CONTENT_REFRESH = "content_refresh"
        private const val CONTENT_WORK_NAME = "spam_content_refresh"
        // 两条任务链共用扫描锁，避免重复识别同一批镜像短信。
        private val scanMutex = Mutex()

        fun schedule(context: Context, replace: Boolean = true, silent: Boolean = false): UUID {
            val request = OneTimeWorkRequestBuilder<HistoricalSpamScanWorker>()
                .setInputData(workDataOf(KEY_SILENT to silent)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        fun scheduleContentRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<HistoricalSpamScanWorker>()
                .setInputData(workDataOf(KEY_SILENT to true, KEY_CONTENT_REFRESH to true)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(CONTENT_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    private val scanner: MirrorSpamScanner by inject()
    private val mirror: MessageMirrorDatabase by inject()

    override suspend fun doWork(): Result = scanMutex.withLock { coroutineScope { scan() } }

    private suspend fun CoroutineScope.scan(): Result {
        val contentRefresh = inputData.getBoolean(KEY_CONTENT_REFRESH, false)
        val silent = inputData.getBoolean(KEY_SILENT, false)
        if (!contentRefresh && !scanner.isEnabled()) return Result.success()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            if (!scanner.isReady()) return Result.retry()
            // 固定镜像上界和内容代次，扫描期间新增、修改的消息留给下一轮。
            val (change, upperLocalId) = mirror.withTransaction {
                mirror.mirrorDao().spamContentChange() to (mirror.mirrorDao().maximumLocalId() ?: 0L)
            }
            if (contentRefresh && change?.complete != false) return Result.success()
            scanner.openSession(parallelism = INFERENCE_PARALLELISM).use { session ->
                var progress = SpamScanProgress(0, scanner.count(session, upperLocalId), 0)
                val reporter = SpamScanProgressReporter(this) { publishProgress(it, silent) }
                try {
                    reporter.batchCompleted(progress)
                    var afterLocalId = 0L
                    while (true) {
                        val committed = progress
                        val batch = scanner.scanBatch(session, afterLocalId, upperLocalId, BATCH_SIZE) { count ->
                            val processed = committed.processed + count
                            reporter.update(committed.copy(processed = processed, total = maxOf(committed.total, processed)))
                        }
                        if (batch.stale || batch.paused) {
                            reporter.update(committed)
                            return if (batch.paused && !contentRefresh) Result.success() else Result.retry()
                        }
                        val processed = progress.processed + batch.processed
                        progress = SpamScanProgress(processed, maxOf(progress.total, processed), progress.spamCount + batch.spamCount)
                        reporter.batchCompleted(progress)
                        afterLocalId = batch.afterLocalId
                        if (batch.complete) break
                    }
                    // 并发删除或实时识别可能减少待处理数，以实际完成数收尾。
                    progress = progress.copy(total = progress.processed)
                    reporter.batchCompleted(progress)
                } finally {
                    reporter.close()
                }
                if (!scanner.complete(session, change?.generation)) return Result.retry()
                runCatching {
                    SmartspacerIntegration.notifyChanged(applicationContext)
                    if (!silent) SpamScanNotificationHelper.showCompleted(applicationContext, progress.processed, progress.spamCount)
                }.onFailure { Log.w(TAG, "spam scan completion notification failed", it) }
                Log.i(TAG, "historical spam scan completed processed=${progress.processed} spam_count=${progress.spamCount} " +
                    "duration_ms=${SystemClock.elapsedRealtime() - startedAt}")
                return Result.success(workDataOf(KEY_TOTAL to progress.total, KEY_PROCESSED to progress.processed,
                    KEY_SPAM_COUNT to progress.spamCount))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: StaleSpamScanSessionException) {
            Log.i(TAG, "historical spam scan retry reason=model_changed")
            return Result.retry()
        } catch (error: Exception) {
            Log.e(TAG, "historical spam scan failed", error)
            if (!silent) runCatching { SpamScanNotificationHelper.showFailed(applicationContext) }
            return if (contentRefresh) Result.retry() else Result.failure()
        }
    }

    /** 只由独立上报协程调用，识别循环不等待此处的数据库或系统通知操作。 */
    private suspend fun publishProgress(progress: SpamScanProgress, silent: Boolean) {
        setProgress(workDataOf(KEY_TOTAL to progress.total, KEY_PROCESSED to progress.processed,
            KEY_SPAM_COUNT to progress.spamCount))
        if (!silent) SpamScanNotificationHelper.showProgress(applicationContext, progress.processed, progress.total)
    }
}
