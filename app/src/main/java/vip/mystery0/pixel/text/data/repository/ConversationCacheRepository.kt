package vip.mystery0.pixel.text.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorIncrementalSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.MirrorChangeObserver
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
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
        MessageMirrorScheduler(context).schedule()
    }
    fun stopObserving() = observer.stop()
    suspend fun isCacheReady(): Boolean {
        val state = mirror.observeSyncState().first()
        return state.completedCollections.containsAll(setOf("SMS", "MMS"))
    }
    suspend fun fullSync(archivedThreadIds: Set<Long>) {
        synchronizer.requestAttachmentVerification()
        synchronizer.reconcile()
        MessageMirrorScheduler(context).schedule()
    }
    suspend fun refreshIncremental() {
        if (!isCacheReady()) {
            synchronizer.reconcile()
        } else {
            val result = incrementalSynchronizer.syncRecent()
            if (result.remaining) incrementalSynchronizer.markWake()
        }
        // 唤醒通知/附件后处理；若增量同步留下需要完整确认的 dirty，Worker 会接管。
        MessageMirrorScheduler(context).schedule()
    }
    suspend fun syncThreads(threadIds: List<Long>) {
        synchronizer.markDirty(null)
        // 系统写入后的通知只持久排队，不让 Receiver 或用户操作等待整库扫描。
        MessageMirrorScheduler(context).enqueueMetadata()
    }
    suspend fun getAllConversations(
        archivedThreadIds: Set<Long>, hiddenThreadIds: Set<Long> = emptySet(),
    ): List<ConversationModel> = mirror.observeConversationSummaries().first()
        .filter { it.threadId !in archivedThreadIds && it.threadId !in hiddenThreadIds }
    fun observeAllConversations(): Flow<List<ConversationModel>> = mirror.observeConversationSummaries()
}
