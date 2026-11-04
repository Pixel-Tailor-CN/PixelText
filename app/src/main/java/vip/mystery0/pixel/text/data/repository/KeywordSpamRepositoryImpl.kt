package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.BlockedKeywordEntity
import vip.mystery0.pixel.text.data.db.KeywordSpamMatchEntity
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.domain.model.BlockedKeyword
import vip.mystery0.pixel.text.domain.model.KeywordSaveResult
import vip.mystery0.pixel.text.domain.model.KeywordSpamMessage
import vip.mystery0.pixel.text.domain.spam.KeywordSpamRepository
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.domain.spam.learning.PersonalLearningRepository
import java.util.Locale

class KeywordSpamRepositoryImpl(
    db: SpamDatabase,
    private val whitelist: SenderWhitelistRepository,
    private val learning: PersonalLearningRepository,
) : KeywordSpamRepository {
    private val dao = db.blockedKeywordDao()

    override fun observeKeywords(): Flow<List<BlockedKeyword>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    // 与收信最终副作用共用决策锁，防止已撤销的关键词继续触发自动操作。
    override suspend fun save(id: Long?, keyword: String): KeywordSaveResult = learning.withDecision {
        withContext(Dispatchers.IO) {
            val trimmed = keyword.trim()
            if (trimmed.isEmpty()) return@withContext KeywordSaveResult.Empty
            val normalized = normalize(trimmed)
            val duplicate = dao.getByNormalizedKeyword(normalized)
            if (duplicate != null && duplicate.id != id) {
                return@withContext KeywordSaveResult.Duplicate
            }

            val now = System.currentTimeMillis()
            if (id == null) {
                val newId = dao.insert(
                    BlockedKeywordEntity(
                        keyword = trimmed,
                        normalizedKeyword = normalized,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                KeywordSaveResult.Success(
                    BlockedKeyword(newId, trimmed, now, now)
                )
            } else {
                val existing = dao.getById(id)
                    ?: return@withContext KeywordSaveResult.NotFound
                val updated = existing.copy(
                    keyword = trimmed,
                    normalizedKeyword = normalized,
                    updatedAt = now,
                )
                dao.update(updated)
                KeywordSaveResult.Success(updated.toDomain())
            }
        }
    }

    override suspend fun delete(id: Long): Boolean = learning.withDecision {
        withContext(Dispatchers.IO) {
            if (dao.getById(id) == null) return@withContext false
            dao.deleteById(id)
            true
        }
    }

    override suspend fun updateMessageMatch(
        messageId: Long,
        threadId: Long,
        content: String,
    ): Boolean = whitelist.withMessageDecision(messageId) { allowed ->
        if (allowed) {
            dao.deleteMatch(messageId)
            return@withMessageDecision false
        }
        val matchedKeyword = findMatch(content, dao.getAll())
        if (matchedKeyword == null) {
            dao.deleteMatch(messageId)
            false
        } else {
            dao.insertMatch(
                KeywordSpamMatchEntity(
                    messageId = messageId,
                    threadId = threadId,
                    keywordId = matchedKeyword.id,
                    matchedAt = System.currentTimeMillis(),
                )
            )
            true
        }
    }

    // 锁顺序保持学习决策锁在前、白名单锁在后。
    override suspend fun rebuildMatches(messages: List<KeywordSpamMessage>): Int = learning.withDecision {
        withContext(Dispatchers.IO) {
            val keywords = dao.getAll()
            val now = System.currentTimeMillis()
            val allowedIds = whitelist.allowedMessageIds(messages.map { it.messageId })
            val matches = messages.mapNotNull { message ->
                if (message.messageId in allowedIds) return@mapNotNull null
                val keyword = findMatch(message.content, keywords) ?: return@mapNotNull null
                KeywordSpamMatchEntity(
                    messageId = message.messageId,
                    threadId = message.threadId,
                    keywordId = keyword.id,
                    matchedAt = now,
                )
            }
            dao.replaceAllMatches(matches)
            matches.size
        }
    }

    private fun findMatch(
        content: String,
        keywords: List<BlockedKeywordEntity>,
    ): BlockedKeywordEntity? {
        if (content.isEmpty() || keywords.isEmpty()) return null
        val normalizedContent = normalize(content)
        return keywords.firstOrNull { it.normalizedKeyword in normalizedContent }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)

    private fun BlockedKeywordEntity.toDomain(): BlockedKeyword = BlockedKeyword(
        id = id,
        keyword = keyword,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
