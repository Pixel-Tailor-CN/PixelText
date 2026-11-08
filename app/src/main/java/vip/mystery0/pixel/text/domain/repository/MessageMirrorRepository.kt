package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.mirror.*
import vip.mystery0.pixel.text.domain.model.search.MessageSearchBatch
import vip.mystery0.pixel.text.domain.model.search.MessageSearchRequest

interface MessageMirrorRepository {
    fun observeSyncState(): Flow<MirrorSyncState>
    suspend fun getMessage(key: SourceMessageKey): MirrorMessageModel?
    fun observeMessage(key: SourceMessageKey): Flow<MirrorMessageModel?>
    suspend fun queryThreadMessages(threadId: Long, limit: Int, offset: Int, filter: ConversationContentFilter): List<MirrorMessageModel>
    fun observeThreadChanges(threadId: Long): Flow<Unit>
    fun observeMmsMessages(): Flow<List<MirrorMessageModel>>
    suspend fun getConversationMessagesByIds(messageIds: Collection<Long>): List<ConversationMessageSnapshot>
    suspend fun queryConversations(query: ConversationListQuery): List<ConversationModel>
    fun observeConversationChanges(): Flow<Unit>
    fun searchMessages(request: MessageSearchRequest): Flow<MessageSearchBatch>
}
