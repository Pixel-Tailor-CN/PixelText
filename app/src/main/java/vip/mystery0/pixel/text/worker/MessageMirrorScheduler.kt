package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import java.util.concurrent.TimeUnit

class MessageMirrorScheduler(context: Context) : KoinComponent {
    private val work = WorkManager.getInstance(context)
    private val synchronizer: MessageMirrorSynchronizer by inject()
    private val database: MessageMirrorDatabase by inject()

    fun schedule(forceReconcile: Boolean = false, reason: String = "provider_event") {
        schedulingScope.launch {
            try {
                enqueueMetadata(forceReconcile, reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MessageMirrorScheduler", "mirror wake failed category=${error.javaClass.simpleName}")
            }
        }
    }

    suspend fun enqueueMetadata(
        forceReconcile: Boolean = false,
        reason: String = "provider_event"
    ) = withContext(Dispatchers.IO) {
        // 强制对账也先持久化，合并到已排队任务时不会丢失调用者的刷新意图。
        if (forceReconcile) synchronizer.markDirty(null)
        schedulingMutex.withLock {
            val existing = work.getWorkInfosForUniqueWork(METADATA_WORK).get().filterNot { it.state.isFinished }
            val running = existing.any { it.state == WorkInfo.State.RUNNING }
            val pending = existing.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            // 新源事件可以唤醒处于退避的元数据任务；不存在运行任务时才替换等待链。
            val wakeRetry = !running && pending.any { it.runAttemptCount > 0 }
            if (pending.isNotEmpty() && !wakeRetry) return@withLock
            work.enqueueUniqueWork(
                METADATA_WORK,
                if (wakeRetry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MessageMirrorWorker>()
                    .setInputData(workDataOf("reason" to if (forceReconcile) "manual_repair" else reason))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
            ).result.get()
        }
    }

    suspend fun enqueueAttachments() = withContext(Dispatchers.IO) {
        schedulingMutex.withLock {
            val existing = work.getWorkInfosForUniqueWork(ATTACHMENT_WORK).get().filterNot { it.state.isFinished }
            val running = existing.any { it.state == WorkInfo.State.RUNNING }
            val pending = existing.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            val wakeRetry = !running && pending.any { it.runAttemptCount > 0 } &&
                database.mirrorDao().pendingAttachments(System.currentTimeMillis()).isNotEmpty()
            if (pending.isNotEmpty() && !wakeRetry) return@withLock
            // 只有运行项时同样保留一个后继，关闭任务退出与新附件入库之间的唤醒窗口。
            work.enqueueUniqueWork(ATTACHMENT_WORK,
                if (wakeRetry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MessageMirrorAttachmentWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
            ).result.get()
        }
    }

    /** 升级旧安装时也撤销已持久化的周期任务，不影响真实事件队列。 */
    fun cancelLegacyPeriodic() {
        work.cancelUniqueWork("message-mirror-reconcile")
    }
    companion object {
        private const val METADATA_WORK = "message-mirror"
        private const val ATTACHMENT_WORK = "message-mirror-attachments"
        private val schedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 所有 Scheduler 实例共享领取锁，最多保留一个运行项和一个等待项。
        private val schedulingMutex = Mutex()
    }
}
