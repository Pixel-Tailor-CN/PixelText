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
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.mms.MmsDownloadCoordinator

/** 附件与下载恢复使用独立任务链，退避不会占住新短信的元数据同步队列。 */
class MessageMirrorAttachmentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    private val synchronizer: MessageMirrorSynchronizer by inject()
    private val downloads: MmsDownloadCoordinator by inject()
    private val incoming: vip.mystery0.pixel.text.mms.MmsIncomingPduHandler by inject()
    private val responses: vip.mystery0.pixel.text.mms.MmsReceptionResponseSender by inject()
    private val notifications: vip.mystery0.pixel.text.mms.MmsReceptionNotifications by inject()
    private val database: MessageMirrorDatabase by inject()
    private val scheduler: MessageMirrorScheduler by inject()

    override suspend fun doWork(): Result {
        if (applicationContext.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        return try {
            val moreIncoming = incoming.recover()
            val moreDownloads = downloads.recover()
            val moreResponses = responses.recover()
            val moreAttachments = synchronizer.copyPendingAttachments()
            notifications.refresh()
            if (!moreIncoming && !moreDownloads && !moreResponses && !moreAttachments) return Result.success()
            if (database.mirrorDao().pendingAttachments(System.currentTimeMillis()).isNotEmpty()) {
                // 仍有立即可处理的附件时续下一片；尚未到 retryAfter 的失败留在独立链中退避。
                scheduler.enqueueAttachments()
                Result.success()
            } else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            Log.w("MessageMirrorAttachmentWorker", "attachment permission unavailable")
            Result.success()
        } catch (error: Exception) {
            Log.w("MessageMirrorAttachmentWorker", "attachment retry category=${error.javaClass.simpleName}")
            Result.retry()
        }
    }
}
