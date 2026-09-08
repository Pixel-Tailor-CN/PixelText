package vip.mystery0.pixel.text.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.mms.MmsDownloadCoordinator

class MessageMirrorWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    private val synchronizer: MessageMirrorSynchronizer by inject()
    private val downloads: MmsDownloadCoordinator by inject()
    private val incoming: vip.mystery0.pixel.text.mms.MmsIncomingPduHandler by inject()
    private val responses: vip.mystery0.pixel.text.mms.MmsReceptionResponseSender by inject()
    private val notifications: vip.mystery0.pixel.text.mms.MmsReceptionNotifications by inject()
    private val scheduler: MessageMirrorScheduler by inject()
    private val database: vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase by inject()

    override suspend fun doWork(): Result {
        if (applicationContext.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        return try {
            // 新下载结果优先尝试持久化；后续失败恢复由独立附件任务退避。
            incoming.recover()
            downloads.recover()
            responses.recover()
            val sync = database.syncDao()
            val needsMetadata = inputData.getBoolean("force_reconcile", false) || sync.dirtyCount() > 0 ||
                listOf("ROUND", "SMS", "MMS", "THREADS", "CANONICAL").any { sync.state(it)?.complete != true }
            if (needsMetadata) synchronizer.reconcile()
            notifications.refresh()
            // reconcile 的总返回值也包含文件清理；清理失败由附件链退避，不阻塞元数据链。
            val moreMetadata = sync.dirtyCount() > 0 ||
                listOf("ROUND", "SMS", "MMS").any { sync.state(it)?.complete != true }
            scheduler.enqueueAttachments()
            if (!moreMetadata) return Result.success()
            val yielded = listOf("SMS", "MMS").any {
                sync.state(it)?.error in setOf("budget_yield", "interrupted_import")
            }
            if (yielded) {
                // 主动预算让出是正常进度，安排下一片而不累积指数退避。
                scheduler.enqueueMetadata()
                Result.success()
            } else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            Log.w("MessageMirrorWorker", "mirror permission unavailable")
            Result.failure()
        } catch (error: Exception) {
            Log.w("MessageMirrorWorker", "mirror retry error=${error.javaClass.simpleName}")
            Result.retry()
        }
    }
}
