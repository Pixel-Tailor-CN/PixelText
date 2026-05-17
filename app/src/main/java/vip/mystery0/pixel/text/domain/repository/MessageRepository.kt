package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel

interface MessageRepository {
    fun getConversations(limit: Int, offset: Int): Flow<List<ConversationModel>>
    fun getArchivedConversations(limit: Int, offset: Int): Flow<List<ConversationModel>>
    fun getSpamConversations(limit: Int, offset: Int): Flow<List<ConversationModel>>
    fun searchConversations(query: String): Flow<List<ConversationModel>>
    fun searchMessages(query: String): Flow<List<MessageModel>>
    fun getMessagesByThread(threadId: Long, limit: Int, offset: Int): Flow<List<MessageModel>>
    fun getMessages(): Flow<List<MessageModel>>
    suspend fun archiveConversations(conversations: List<ConversationModel>)
    suspend fun unarchiveThreads(threadIds: Set<Long>)
    suspend fun deleteThreads(threadIds: Set<Long>)
    suspend fun markThreadAsRead(threadId: Long)
}
