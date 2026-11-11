package vip.mystery0.pixel.text.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.db.BlockedKeywordEntity
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamLegacyImporter
import vip.mystery0.pixel.text.data.db.mirror.MirrorSyncStateEntity
import vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.model.BlockedKeyword
import vip.mystery0.pixel.text.domain.model.KeywordSaveResult
import vip.mystery0.pixel.text.domain.model.KeywordSpamMessage
import vip.mystery0.pixel.text.domain.spam.KeywordSpamRepository
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.domain.spam.learning.PersonalLearningRepository
import java.util.Locale

class KeywordSpamRepositoryImpl(
    private val mirror: MessageMirrorDatabase,
    private val whitelist: SenderWhitelistRepository,
    private val learning: PersonalLearningRepository,
    private val importer: MirrorSpamLegacyImporter,
    private val synchronizer: MessageMirrorSynchronizer,
) : KeywordSpamRepository {
    private val dao = mirror.blockedKeywordDao()
    private val decisions = mirror.spamDao()

    override fun observeKeywords(): Flow<List<BlockedKeyword>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    // 与收信最终副作用共用决策锁；规则操作返回时，既有消息的标记已经更新。
    override suspend fun save(id: Long?, keyword: String): KeywordSaveResult {
        importer.ensureImported()
        return learning.withDecision {
            withContext(Dispatchers.IO) {
                val trimmed = keyword.trim()
                if (trimmed.isEmpty()) return@withContext KeywordSaveResult.Empty
                val normalized = normalize(trimmed)
                val duplicate = dao.getByNormalizedKeyword(normalized)
                if (duplicate != null && duplicate.id != id) return@withContext KeywordSaveResult.Duplicate
                val now = System.currentTimeMillis()
                val existing = if (id == null) null else dao.getById(id)
                if (id != null && existing == null) return@withContext KeywordSaveResult.NotFound
                val saved = MirrorSynchronizationLock.mutex.withLock {
                    mirror.withTransaction {
                        val entity = if (existing == null) {
                            val row = BlockedKeywordEntity(keyword = trimmed, normalizedKeyword = normalized,
                                createdAt = now, updatedAt = now)
                            row.copy(id = dao.insert(row))
                        } else {
                            existing.copy(keyword = trimmed, normalizedKeyword = normalized, updatedAt = now)
                                .also { dao.update(it) }
                        }
                        rebuildKeywordProjection(mirror)
                        entity
                    }
                }
                KeywordSaveResult.Success(saved.toDomain())
            }
        }
    }

    override suspend fun delete(id: Long): Boolean {
        importer.ensureImported()
        return learning.withDecision {
            withContext(Dispatchers.IO) {
                if (dao.getById(id) == null) return@withContext false
                MirrorSynchronizationLock.mutex.withLock {
                    mirror.withTransaction {
                        dao.deleteById(id)
                        rebuildKeywordProjection(mirror)
                    }
                }
                true
            }
        }
    }

    override suspend fun updateMessageMatch(messageId: Long): Boolean {
        importer.ensureImported()
        // 此入口也在学习决策锁内部调用，不递归获取同一锁。
        return whitelist.withMessageDecision(messageId) { allowed ->
            MirrorSynchronizationLock.mutex.withLock {
                val row = mirror.mirrorDao().conversationSmsByIds(listOf(messageId)).firstOrNull()
                    ?: return@withLock false
                val versions = mapOf(messageId to (row.localId to row.revision))
                val keyword = if (allowed) null else findMatch(row.body.orEmpty(), dao.getAll())
                decisions.setKeyword(messageId, keyword?.id, versions)
                keyword != null
            }
        }
    }

    override suspend fun rebuildAllMatches(): Int {
        importer.ensureImported()
        return learning.withDecision {
            withContext(Dispatchers.IO) {
                rebuildCurrentMatches()
            }
        }
    }

    override suspend fun rebuildMatches(messages: List<KeywordSpamMessage>): Int {
        importer.ensureImported()
        return learning.withDecision {
            withContext(Dispatchers.IO) {
                // 先批量比较正文，只同步差异，避免历史扫描逐条重写几千条未变化短信。
                val existing = messages.asSequence().map { it.messageId }.filter { it > 0 }.chunked(500).toList()
                    .flatMap { mirror.mirrorDao().conversationSmsByIds(it) }.associateBy { it.sourceId }
                messages.filter { it.messageId > 0 }.forEach { message ->
                    val row = existing[message.messageId]
                    if (row == null || row.body.orEmpty() != message.content || row.threadId != message.threadId) {
                        check(synchronizer.refreshInitializationMessage(SourceMessageKey(MessageTransport.SMS, message.messageId))) {
                            "message synchronization incomplete"
                        }
                    }
                }
                whitelist.allowedMessageIds(messages.map { it.messageId })
                rebuildCurrentMatches()
            }
        }
    }

    /** 只修复旧版跨库写入遗留的中断标记，新写入与规则在同一事务内提交。 */
    suspend fun reconcileMessageKeywordsIfNeeded() = learning.withDecision {
        if (mirror.syncDao().state(PROJECTION_STATE)?.complete == false) rebuildCurrentMatches()
    }

    private suspend fun rebuildCurrentMatches(): Int = MirrorSynchronizationLock.mutex.withLock {
        mirror.withTransaction { rebuildKeywordProjection(mirror) }
    }

    private fun findMatch(content: String, keywords: List<BlockedKeywordEntity>): BlockedKeywordEntity? {
        if (content.isEmpty() || keywords.isEmpty()) return null
        val normalizedContent = normalize(content)
        return keywords.firstOrNull { it.normalizedKeyword in normalizedContent }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)

    private fun BlockedKeywordEntity.toDomain() = BlockedKeyword(id, keyword, createdAt, updatedAt)

    private companion object {
        const val PROJECTION_STATE = "keyword_decision_projection"
    }
}

/** 调用方持有镜像同步锁和主库事务，保证规则与消息最终标记同时提交。 */
internal suspend fun rebuildKeywordProjection(mirror: MessageMirrorDatabase): Int {
    val keywords = mirror.blockedKeywordDao().getAll()
    val decisions = mirror.spamDao()
    val sms = decisions.keywordCandidates()
    val versions = sms.associate { it.sourceId to (it.localId to it.revision) }
    val matches = sms.mapNotNull { message ->
        if (message.spamAllowedSource != null) return@mapNotNull null
        val content = message.body.orEmpty().lowercase(Locale.ROOT)
        keywords.firstOrNull { it.normalizedKeyword in content }?.let { message.sourceId to it.id }
    }.toMap()
    decisions.replaceKeywords(matches, versions)
    mirror.syncDao().putState(MirrorSyncStateEntity("keyword_decision_projection", complete = true))
    return matches.size
}
