package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.mirror.*

interface MessageMirrorRepository {
    fun observeSyncState(): Flow<MirrorSyncState>
    suspend fun getMessage(key: SourceMessageKey): MirrorMessageModel?
    fun observeMessage(key: SourceMessageKey): Flow<MirrorMessageModel?>
    fun observeMessagesByThread(threadId: Long, limit: Int, offset: Int): Flow<List<MirrorMessageModel>>
    fun observeThreadChanges(threadId: Long): Flow<Unit>
    fun observeAllMessages(): Flow<List<MirrorMessageModel>>
    fun observeConversationSummaries(): Flow<List<ConversationModel>>
}
