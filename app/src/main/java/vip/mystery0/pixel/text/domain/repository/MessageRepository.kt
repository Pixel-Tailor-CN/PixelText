package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.MessageModel

interface MessageRepository {
    fun getMessages(): Flow<List<MessageModel>>
}
