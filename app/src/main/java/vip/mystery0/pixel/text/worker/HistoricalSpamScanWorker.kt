package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.data.source.SpamScanMessageRow
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.repository.ConversationCacheRepository
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.spam.KeywordSpamRepository
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.notification.SpamScanNotificationHelper
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HistoricalSpamScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        private const val TAG = "HistoricalSpamScanWorker"
        private const val BATCH_SIZE = 200
        private const val SPAM_THRESHOLD = 0.7f
        private const val NOTIFICATION_UPDATE_INTERVAL = 5

        const val UNIQUE_WORK_NAME = "historical_spam_scan"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_SPAM_COUNT = "spam_count"
        private const val KEY_SILENT = "silent"
        private const val KEY_CONTENT_REFRESH = "content_refresh"
        private const val CONTENT_WORK_NAME = "spam_content_refresh"
        // 两条唯一任务链共享扫描资源，避免启动补扫和内容补扫重复推理同一批消息。
        private val scanMutex = Mutex()

        fun schedule(context: Context, replace: Boolean = true, silent: Boolean = false): UUID {
            val request = OneTimeWorkRequestBuilder<HistoricalSpamScanWorker>()
                .setInputData(workDataOf(KEY_SILENT to silent)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
            return request.id
        }

        fun scheduleContentRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<HistoricalSpamScanWorker>()
                .setInputData(workDataOf(KEY_SILENT to true, KEY_CONTENT_REFRESH to true)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(CONTENT_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    private val telephonyDataSource: TelephonyDataSource by inject()
    private val spamRepository: SpamRepository by inject()
    private val whitelist: vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository by inject()
    private val spamClassifierFactory: SpamClassifierFactory by inject()
    private val mirror: MessageMirrorDatabase by inject()
    private val keywords: KeywordSpamRepository by inject()
    private val cache: ConversationCacheRepository by inject()
    private val synchronizer: MessageMirrorSynchronizer by inject()

    override suspend fun doWork(): Result = scanMutex.withLock { scan() }

    private suspend fun scan(): Result {
        val contentRefresh = inputData.getBoolean(KEY_CONTENT_REFRESH, false)
        if (!contentRefresh && !spamRepository.isEnabled()) return Result.success()

        return try {
            if (contentRefresh && mirror.mirrorDao().spamContentChange()?.complete != false) return Result.success()
            cache.refreshIncremental(requireComplete = true)
            spamRepository.ensureReady()
            // 在读取本轮源数据前捕获代次，之后出现的新消息不属于这次完成确认。
            val contentGeneration = mirror.mirrorDao().spamContentChange()?.generation
            val allMessages = telephonyDataSource.getSmsMessagesForSpamScan()
            // 应用关闭期间可能复用旧 Provider ID。先批量比较扫描正文，只同步真正变化的行，
            // 不能以旧镜像里的模型版本为依据跳过新消息，也不为此开启整库附件对账。
            synchronizeScanMessages(allMessages)
            val hasKeywords = mirror.blockedKeywordDao().getAll().isNotEmpty()
            if (!spamRepository.isEnabled()) {
                val unknownIds = mirror.spamDao().rows(allMessages.map { it.messageId })
                    .filter { it.isSpam == null }.mapTo(mutableSetOf()) { it.messageId }
                val unknown = allMessages.filter { it.messageId in unknownIds }
                if (hasKeywords) {
                    unknown.forEach { keywords.updateMessageMatch(it.messageId, it.threadId, it.content) }
                } else if (unknownIds.isNotEmpty()) {
                    whitelist.allowedMessageIds(unknownIds)
                }
                contentGeneration?.let { mirror.mirrorDao().completeSpamContentChange(it) }
                SmartspacerIntegration.notifyChanged(applicationContext)
                return Result.success()
            }
            val identifiedIds =
                spamRepository.getCurrentModelMessageIds(allMessages.map { it.messageId })
            val pendingMessages = allMessages.filter { it.messageId !in identifiedIds }

            var processed = 0
            var spamCount = 0
            publishProgress(processed, pendingMessages.size, spamCount, forceNotification = true)

            if (pendingMessages.isNotEmpty()) {
                spamClassifierFactory.create().use { classifier ->
                    pendingMessages.chunked(BATCH_SIZE).forEach { batch ->
                        batch.forEach { message ->
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (!spamRepository.isEnabled()) {
                                // 本轮尚未更新完关键词时不能吞掉待处理代次。
                                if (contentRefresh) return Result.retry()
                                return Result.success()
                            }
                            if (hasKeywords) keywords.updateMessageMatch(message.messageId, message.threadId, message.content)
                            val prediction = if (whitelist.isAllowed(message.messageId) || message.content.isBlank()) null
                                else classifier.predict(message.content)
                            if (prediction != null) {
                                if (!spamRepository.savePrediction(message.messageId, message.threadId, prediction, vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey.create("SMS", message.messageId, message.address, message.date, message.content))) return Result.retry()
                                if ((spamRepository.getScore(message.messageId) ?: -1f) >= SPAM_THRESHOLD) spamCount++
                            }
                            processed++
                            publishProgress(processed, pendingMessages.size, spamCount)
                        }
                    }
                }
            }

            publishProgress(processed, pendingMessages.size, spamCount, forceNotification = true)
            if (pendingMessages.isNotEmpty()) {
                SmartspacerIntegration.notifyChanged(applicationContext)
            }
            if (!inputData.getBoolean(KEY_SILENT, false)) SpamScanNotificationHelper.showCompleted(applicationContext, processed, spamCount)
            // 新消息在扫描期间写入会推进 generation，条件更新保留下一轮工作。
            contentGeneration?.let { mirror.mirrorDao().completeSpamContentChange(it) }
            Result.success(
                workDataOf(
                    KEY_TOTAL to pendingMessages.size,
                    KEY_PROCESSED to processed,
                    KEY_SPAM_COUNT to spamCount
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "historical spam scan failed", e)
            if (!inputData.getBoolean(KEY_SILENT, false)) SpamScanNotificationHelper.showFailed(applicationContext)
            if (contentRefresh) Result.retry() else Result.failure()
        }
    }

    private suspend fun synchronizeScanMessages(messages: List<SpamScanMessageRow>) {
        val current = messages.map { it.messageId }.chunked(500)
            .flatMap { mirror.mirrorDao().conversationSmsByIds(it) }.associateBy { it.sourceId }
        for (message in messages) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val row = current[message.messageId]
            if (row != null && row.originalDate == message.date && row.address.orEmpty() == message.address &&
                row.body.orEmpty() == message.content) continue
            check(synchronizer.refreshInitializationMessage(SourceMessageKey(MessageTransport.SMS, message.messageId))) {
                "message synchronization incomplete"
            }
        }
    }

    private suspend fun publishProgress(
        processed: Int,
        total: Int,
        spamCount: Int,
        forceNotification: Boolean = false
    ) {
        setProgress(
            workDataOf(
                KEY_TOTAL to total,
                KEY_PROCESSED to processed,
                KEY_SPAM_COUNT to spamCount
            )
        )

        if (!inputData.getBoolean(KEY_SILENT, false) && (forceNotification ||
            processed == total ||
            processed % NOTIFICATION_UPDATE_INTERVAL == 0)
        ) {
            SpamScanNotificationHelper.showProgress(applicationContext, processed, total)
        }
    }
}
