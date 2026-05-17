package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.db.SpamResultEntity
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository

class SpamRepositoryImpl(
    db: SpamDatabase,
    private val settingsRepository: AppSettingsRepository
) : SpamRepository {
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

    override suspend fun getIdentifiedMessageIds(messageIds: List<Long>): Set<Long> =
        withContext(Dispatchers.IO) {
            if (messageIds.isEmpty()) {
                emptySet()
            } else {
                messageIds.chunked(MAX_QUERY_ARGS)
                    .flatMap { dao.getExistingMessageIds(it) }
                    .toSet()
            }
        }

    override suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long> =
        withContext(Dispatchers.IO) { dao.getSpamThreadIds(threshold, limit, offset) }

    override fun isEnabled(): Boolean = settingsRepository.isSpamDetectionEnabled()

    private companion object {
        private const val MAX_QUERY_ARGS = 900
    }
}
