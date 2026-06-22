package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel

data class MessageSearchFilter(
    val unreadOnly: Boolean = false,
    val simSubId: Int? = null,
    val mmsOnly: Boolean = false
)

interface MessageRepository {
    fun startCacheObserving()
    suspend fun isCacheReady(): Boolean
    fun getAllConversations(): Flow<List<ConversationModel>>
    fun getArchivedConversations(limit: Int, offset: Int): Flow<List<ConversationModel>>
    fun getSpamConversations(limit: Int, offset: Int): Flow<List<ConversationModel>>
    fun searchConversations(query: String): Flow<List<ConversationModel>>
    fun searchMessages(
        query: String,
        filter: MessageSearchFilter = MessageSearchFilter()
    ): Flow<List<MessageModel>>
    fun getMessagesByThread(threadId: Long, limit: Int, offset: Int): Flow<List<MessageModel>>
    fun getMessages(): Flow<List<MessageModel>>
    suspend fun archiveConversations(conversations: List<ConversationModel>)
    suspend fun unarchiveThreads(threadIds: Set<Long>)
    suspend fun deleteThreads(threadIds: Set<Long>)
    suspend fun deleteMessages(messageIds: Set<Long>): Int
    suspend fun markThreadAsRead(threadId: Long)
    suspend fun markThreadsAsRead(threadIds: Set<Long>)
    suspend fun markThreadAsUnread(threadId: Long)
}
