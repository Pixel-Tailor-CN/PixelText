package vip.mystery0.pixel.text.data.repository

import android.content.Context
import kotlinx.coroutines.flow.first
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorIncrementalSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.MirrorChangeObserver
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.data.source.requireSmsAccess
import vip.mystery0.pixel.text.data.source.smsAccessState
import vip.mystery0.pixel.text.data.source.SmsAccessState
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler

/** 兼容旧接口；会话是本地消息的投影，不再独立扫描系统摘要。 */
class ConversationCacheRepository(
    private val context: Context,
    private val mirror: MessageMirrorRepository,
    private val synchronizer: MessageMirrorSynchronizer,
    private val incrementalSynchronizer: MessageMirrorIncrementalSynchronizer,
    private val observer: MirrorChangeObserver,
) {
    fun startObserving() {
        observer.start()
    }
    suspend fun isCacheReady(): Boolean {
        val state = mirror.observeSyncState().first()
        return state.completedCollections.containsAll(setOf("SMS", "MMS"))
    }
    suspend fun fullSync() {
        context.requireSmsAccess()
        synchronizer.requestAttachmentVerification()
        synchronizer.reconcile()
        MessageMirrorScheduler(context).schedule(reason = "manual_repair")
    }
    /**
     * 推动镜像追平系统短信。调用方不应以此阻塞首屏：镜像写入会触发会话表变化并重新发布。
     * [reconcileIfRequired] 为 true 时，增量无法确认的变更（如删除）在本次调用内完成全量对账。
     */
    suspend fun refreshIncremental(reconcileIfRequired: Boolean = false) {
        // 展示已有镜像不要求系统权限，也不向 Provider 发起查询。
        if (context.smsAccessState() != SmsAccessState.READY) return
        if (!isCacheReady()) {
            synchronizer.reconcile()
        }
        var result = incrementalSynchronizer.syncRecent()
        if (reconcileIfRequired && result.requiresFullReconcile) {
            synchronizer.reconcile()
            result = incrementalSynchronizer.syncRecent()
        }
        if (result.remaining) incrementalSynchronizer.markWake()
        // 唤醒通知/附件后处理；若增量同步留下需要完整确认的 dirty，Worker 会接管。
        MessageMirrorScheduler(context).schedule()
    }
    suspend fun syncIncomingSms(messageId: Long) {
        // 新收短信已有确切身份，按消息排队即可；全量对账会让新消息等待历史扫描。
        synchronizer.markDirty(
            vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey(
                vip.mystery0.pixel.text.domain.model.mirror.MessageTransport.SMS,
                messageId,
            )
        )
        MessageMirrorScheduler(context).enqueueMetadata(reason = "incoming_sms")
    }
    suspend fun syncLocalChanges() {
        synchronizer.markDirty(null)
        // 系统写入后的通知只持久排队，不让 Receiver 或用户操作等待整库扫描。
        MessageMirrorScheduler(context).enqueueMetadata(reason = "local_write")
    }
}
