package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.db.SpamResultEntity
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository

class SpamRepositoryImpl(
    db: SpamDatabase,
    private val settingsRepository: AppSettingsRepository,
    private val whitelist: SenderWhitelistRepository,
) : SpamRepository {
    private val dao = db.spamResultDao()
    private val keywordDao = db.blockedKeywordDao()

    override suspend fun getScore(messageId: Long): Float? =
        withContext(Dispatchers.IO) { if (whitelist.isAllowed(messageId)) 0f else dao.getScore(messageId) }

    override suspend fun save(messageId: Long, threadId: Long, score: Float) =
        whitelist.withMessageDecision(messageId) { allowed ->
            if (allowed) return@withMessageDecision
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
                    .flatMap { dao.getExistingMessageIds(it) + whitelist.allowedMessageIds(it) }
                    .toSet()
            }
        }

    override suspend fun getSpamMessageIds(messageIds: List<Long>, threshold: Float): Set<Long> =
        withContext(Dispatchers.IO) {
            if (messageIds.isEmpty()) {
                emptySet()
            } else {
                messageIds.chunked(MAX_QUERY_ARGS)
                    .flatMap {
                        val candidates = dao.getSpamMessageIds(it, threshold)
                        candidates - whitelist.allowedMessageIds(candidates)
                    }
                    .toSet()
            }
        }

    override suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long> =
        withContext(Dispatchers.IO) {
            // 先验证消息身份、持久化新命中的放行，再在 SQL 中过滤后分页，避免空页或数量错误。
            whitelist.allowedMessageIds(dao.getCandidateSpamMessageIds(threshold))
            dao.getSpamThreadIds(threshold, limit, offset)
        }

    override suspend fun delete(messageIds: Set<Long>) {
        if (messageIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            messageIds.chunked(MAX_QUERY_ARGS).forEach {
                dao.deleteByMessageIds(it)
                keywordDao.deleteMatches(it)
                whitelist.forget(it)
            }
        }
    }

    override fun observeChanges(): Flow<Unit> = dao.observeCount().map { }

    override fun isEnabled(): Boolean = settingsRepository.isSpamDetectionEnabled()

    private companion object {
        private const val MAX_QUERY_ARGS = 900
    }
}
