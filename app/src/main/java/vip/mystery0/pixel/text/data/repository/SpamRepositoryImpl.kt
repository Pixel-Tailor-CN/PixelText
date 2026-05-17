package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.db.SpamResultEntity
import vip.mystery0.pixel.text.domain.spam.SpamRepository

class SpamRepositoryImpl(db: SpamDatabase) : SpamRepository {
    private val dao = db.spamResultDao()

    override suspend fun getScore(messageId: Long): Float? =
        withContext(Dispatchers.IO) { dao.getScore(messageId) }

    override suspend fun save(messageId: Long, threadId: Long, score: Float) =
        withContext(Dispatchers.IO) {
            dao.insert(
                SpamResultEntity(
                    messageId = messageId,
                    threadId = threadId,
                    spamScore = score,
                    checkedAt = System.currentTimeMillis()
                )
            )
        }

    override suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long> =
        withContext(Dispatchers.IO) { dao.getSpamThreadIds(threshold, limit, offset) }

    override fun isEnabled(): Boolean = true
}
