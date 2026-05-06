package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel

interface MessageRepository {
    fun getConversations(): Flow<List<ConversationModel>>
    fun getMessagesByThread(threadId: Long, limit: Int, offset: Int): Flow<List<MessageModel>>
    fun getMessages(): Flow<List<MessageModel>>
}
