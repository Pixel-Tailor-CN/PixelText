package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.domain.spam.SpamRepository

class SpamRepositoryImpl(private val db: SpamDatabase) : SpamRepository {
    override suspend fun getScore(messageId: Long): Float? =
        withContext(Dispatchers.IO) { db.getScore(messageId) }

    override suspend fun save(messageId: Long, threadId: Long, score: Float) =
        withContext(Dispatchers.IO) { db.insert(messageId, threadId, score) }

    override fun isEnabled(): Boolean = true
}
