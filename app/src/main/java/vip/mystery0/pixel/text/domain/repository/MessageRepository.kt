package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.search.MessageSearchBatch
import vip.mystery0.pixel.text.domain.model.search.MessageSearchRequest

enum class ConversationContentFilter {
    ALL,
    NORMAL,
    SPAM,
}

interface MessageRepository {
    fun startCacheObserving()
    suspend fun isCacheReady(): Boolean
    suspend fun refreshConversations()
    suspend fun forceSyncConversations()
    fun getAllConversations(): Flow<List<ConversationModel>>
    fun getArchivedConversations(limit: Int, offset: Int): Flow<List<ConversationModel>>
    fun getSpamConversations(limit: Int, offset: Int): Flow<List<ConversationModel>>
    fun searchConversations(query: String): Flow<List<ConversationModel>>
    fun searchMessages(request: MessageSearchRequest): Flow<MessageSearchBatch>
    fun getMessagesByThread(
        threadId: Long,
        limit: Int,
        offset: Int,
        contentFilter: ConversationContentFilter = ConversationContentFilter.ALL,
    ): Flow<List<MessageModel>>
    fun getMessages(): Flow<List<MessageModel>>
    suspend fun archiveConversations(conversations: List<ConversationModel>)
    suspend fun unarchiveThreads(threadIds: Set<Long>)
    suspend fun deleteThreads(threadIds: Set<Long>)
    suspend fun deleteMessages(messageIds: Set<Long>): Int
    suspend fun markMessagesAsRead(messageIds: Set<Long>): Int
    suspend fun markThreadAsRead(threadId: Long)
    suspend fun markThreadsAsRead(threadIds: Set<Long>)
    suspend fun markThreadAsUnread(threadId: Long)
}
