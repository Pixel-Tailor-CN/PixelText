package vip.mystery0.pixel.text.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.SenderWhitelistRuleEntity
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamLegacyImporter
import vip.mystery0.pixel.text.data.db.mirror.MirrorSyncStateEntity
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.data.source.WhitelistMessageIdentity
import vip.mystery0.pixel.text.data.source.WhitelistMessageSource
import vip.mystery0.pixel.text.data.source.whitelistMessageFingerprint
import vip.mystery0.pixel.text.data.source.mirror.ProviderRowSnapshot
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistMatcher
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRule
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType
import vip.mystery0.pixel.text.domain.spam.MessageSpamSource

class SenderWhitelistRepositoryImpl(
    private val db: SpamDatabase,
    private val source: WhitelistMessageSource,
    private val mirror: MessageMirrorDatabase,
    private val importer: MirrorSpamLegacyImporter,
    private val synchronizer: MessageMirrorSynchronizer,
) : SenderWhitelistRepository {
    private val dao = db.senderWhitelistDao()
    private val decisions = mirror.spamDao()
    private val decisionMutex = Mutex()
    private var cachedRules = emptyList<SenderWhitelistRuleEntity>()
    private var cachedMatchers = emptyList<SenderWhitelistMatcher>()
    private var projectionChecked = false

    override fun observeRules() = dao.observeRules().map { rows ->
        rows.map { SenderWhitelistRule(it.id, WhitelistRuleType.valueOf(it.type), it.value) }
    }

    override suspend fun save(id: Long?, type: WhitelistRuleType, input: String) = withContext(Dispatchers.IO) {
        importer.ensureImported()
        val values = if (type == WhitelistRuleType.EXACT) {
            input.split('\n', ',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty).distinct()
        } else listOf(input.trim())
        require(values.isNotEmpty()) { "请输入号码或表达式" }
        require(id == null || values.size == 1) { "编辑时只能输入一条规则" }
        values.forEach { value -> SenderWhitelistMatcher.validate(type, value)?.let { error(it) } }
        decisionMutex.withLock {
            val current = dao.getRules()
            require(id == null || current.any { it.id == id }) { "规则不存在，请刷新后重试" }
            require(current.count { it.id != id } + values.size <= SenderWhitelistMatcher.MAX_RULES) {
                "最多保存 ${SenderWhitelistMatcher.MAX_RULES} 条规则"
            }
            require(values.none { value -> current.any { it.id != id && it.type == type.name && it.value == value } }) {
                "包含已存在的规则，请删除重复项后重试"
            }
            val now = System.currentTimeMillis()
            val additions = values.map { SenderWhitelistRuleEntity(id ?: 0, type.name, it, now) }
            val history = source.load()
            // 旧规则覆盖的消息先持久放行，新规则提交后再更新其匹配结果。
            persistMatches(history, compile(current))
            markProjectionDirty()
            db.withTransaction {
                // 编辑前先将旧规则和新规则覆盖的既有消息持久放行，不能随规则变化撤销。
                if (id != null) dao.deleteRule(id)
                dao.insertRules(additions)
            }
            persistMatches(history, compile(dao.getRules()))
            completeProjection()
        }
    }

    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        importer.ensureImported()
        decisionMutex.withLock {
            val current = dao.getRules()
            require(current.any { it.id == id }) { "规则不存在，请刷新后重试" }
            val history = source.load()
            persistMatches(history, compile(current))
            markProjectionDirty()
            db.withTransaction {
                // 也涵盖已入 Provider、尚在等待后台检测的新消息。
                dao.deleteRule(id)
            }
            completeProjection()
        }
    }

    override suspend fun allowedMessageIds(messageIds: Collection<Long>): Set<Long> = withContext(Dispatchers.IO) {
        importer.ensureImported()
        decisionMutex.withLock { allowedLocked(messageIds) }
    }

    override suspend fun <T> withMessageDecision(messageId: Long, action: suspend (Boolean) -> T): T =
        withContext(Dispatchers.IO) {
            importer.ensureImported()
            decisionMutex.withLock {
                action(messageId in allowedLocked(listOf(messageId)))
            }
        }

    // 放行字段属于消息行，删除和身份变化由镜像事务清理，迟到的回调不能清除新消息。
    override suspend fun forget(messageIds: Collection<Long>) = Unit

    /** 合并备份只添加规则，不撤销已有规则；与收信决策共用锁。 */
    suspend fun mergeBackupRules(
        keywords: List<vip.mystery0.pixel.text.data.db.BlockedKeywordEntity>,
        rules: List<SenderWhitelistRuleEntity>,
    ) = withContext(Dispatchers.IO) {
        importer.ensureImported()
        decisionMutex.withLock {
            rules.forEach { require(SenderWhitelistMatcher.validate(WhitelistRuleType.valueOf(it.type), it.value) == null) }
            markProjectionDirty()
            // 备份也会合并关键词，跨库中断时交给相同的启动恢复入口处理。
            mirror.syncDao().putState(MirrorSyncStateEntity("keyword_decision_projection", complete = false))
            db.withTransaction {
                val current = dao.getRules().map { it.type to it.value }.toSet()
                val additions = rules.distinctBy { it.type to it.value }.filter { (it.type to it.value) !in current }
                require(current.size + additions.size <= SenderWhitelistMatcher.MAX_RULES) { "合并后白名单超过 500 条" }
                val keywordDao = db.blockedKeywordDao()
                keywords.forEach { row ->
                    if (keywordDao.getByNormalizedKeyword(row.normalizedKeyword) == null) keywordDao.insert(row.copy(id = 0))
                }
                if (additions.isNotEmpty()) dao.insertRules(additions.map { it.copy(id = 0) })
            }
            persistMatches(source.load(), compile(dao.getRules()))
            completeProjection()
        }
    }

    private suspend fun allowedLocked(ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val rules = dao.getRules()
        // 没有任何放行依据时，历史扫描无需为白名单重写整个镜像。
        // 单条写入仍须同步，因为收信检测可能早于镜像观察者。
        if (ids.size > 1 && rules.isEmpty() && decisions.rows(ids.toList()).none { it.spamAllowedSource != null }) {
            return emptySet()
        }
        // 这里只在写入/自动处置入口核对系统消息，列表直接读取已经落库的最终状态。
        ensureMirrored(ids)
        return MirrorSynchronizationLock.mutex.withLock {
            val rows = ids.chunked(500).flatMap { decisions.rows(it) }
            val versions = rows.associate { it.messageId to (it.localId to it.messageRevision) }
            val existing = rows
                .filter { it.spamAllowedSource != null }.mapTo(mutableSetOf()) { it.messageId }
            if (existing.isEmpty() && rules.isEmpty()) return@withLock emptySet()
            val messages = source.load(ids.filterNot { it in existing })
            val matchers = compile(rules)
            val additions = messages.filter { message -> matchers.any { it.matches(message.sender) } }.map { it.id }
            if (additions.isNotEmpty()) decisions.allow(additions, expectedVersions = versions)
            ids.chunked(500).flatMap { decisions.rows(it) }
                .filter { it.spamAllowedSource != null }.mapTo(mutableSetOf()) { it.messageId }
        }
    }

    private suspend fun persistMatches(messages: List<WhitelistMessageIdentity>, matchers: List<SenderWhitelistMatcher>) {
        if (matchers.isEmpty()) return
        val matched = messages.filter { message -> matchers.any { it.matches(message.sender) } }
        val ids = matched.map { it.id }
        ensureMirrored(ids)
        MirrorSynchronizationLock.mutex.withLock {
            val versions = decisions.rows(ids).associate { it.messageId to (it.localId to it.messageRevision) }
            // 同步期间 Provider 可能发生变化，只放行当前仍匹配规则的身份。
            val current = source.load(ids).filter { message -> matchers.any { it.matches(message.sender) } }
            decisions.allow(current.map { it.id }, expectedVersions = versions)
        }
    }

    /** 规则已提交但消息更新尚未完成时，重启只补做这次更新。 */
    suspend fun reconcileMessageWhitelistIfNeeded() = decisionMutex.withLock {
        if (projectionChecked) return@withLock
        if (mirror.syncDao().state(PROJECTION_STATE)?.complete == false) {
            persistMatches(source.load(), compile(dao.getRules()))
            completeProjection()
        }
        projectionChecked = true
    }

    private suspend fun markProjectionDirty() {
        projectionChecked = false
        mirror.syncDao().putState(MirrorSyncStateEntity(PROJECTION_STATE, complete = false))
    }

    private suspend fun completeProjection() {
        val previouslyAllowed = decisions.allRows()
            .filter { it.spamAllowedSource == MessageSpamSource.WHITELIST }.map { it.messageId }
        if (previouslyAllowed.isNotEmpty()) {
            ensureMirrored(previouslyAllowed)
            MirrorSynchronizationLock.mutex.withLock {
                val versions = decisions.rows(previouslyAllowed)
                    .associate { it.messageId to (it.localId to it.messageRevision) }
                val matchers = compile(dao.getRules())
                val released = source.load(previouslyAllowed)
                    .filter { message -> matchers.none { it.matches(message.sender) } }.map { it.id }
                // 规则删改不撤销历史放行；明确记录它已经属于持久放行，而非当前规则命中。
                decisions.allow(released, MessageSpamSource.RELEASED, versions)
            }
        }
        mirror.syncDao().putState(MirrorSyncStateEntity(PROJECTION_STATE, complete = true))
        projectionChecked = true
    }

    private suspend fun ensureMirrored(ids: Collection<Long>) {
        val requested = ids.distinct().filter { it != 0L && it != Long.MIN_VALUE }
        if (requested.isEmpty()) return
        val changed = if (requested.size == 1) requested else {
            val current = source.load(requested).associate { it.id to it.fingerprint }
            val mirrored = buildMap {
                requested.filter { it > 0 }.chunked(500).forEach { batch ->
                    mirror.mirrorDao().conversationSmsByIds(batch).forEach { row ->
                        put(row.sourceId, whitelistMessageFingerprint(row.sourceId, row.address.orEmpty(),
                            row.originalDate ?: 0L, row.dateSent ?: 0L))
                    }
                }
                requested.filter { it < 0 }.map { -it }.chunked(500).forEach { batch ->
                    mirror.mirrorDao().conversationMmsByIds(batch).forEach { row ->
                        val addresses = row.addresses.sortedBy { it.ordinal }
                            .filter { !it.address.isNullOrBlank() && it.address != "insert-address-token" }
                        val sender = addresses.firstOrNull { it.type == 137 }?.address
                            ?: addresses.firstOrNull()?.address.orEmpty()
                        val sent = row.mms?.rawSnapshot?.let { ProviderRowSnapshot.decode(it).long("date_sent") } ?: 0L
                        val id = -row.message.sourceId
                        put(id, whitelistMessageFingerprint(id, sender, row.message.originalDate ?: 0L, sent))
                    }
                }
            }
            // 批量身份比较只读数据库；只有缺失、删除或身份变化才触发镜像更新。
            requested.filter { current[it] != mirrored[it] }
        }
        for (id in changed) {
            val key = SourceMessageKey(if (id > 0) MessageTransport.SMS else MessageTransport.MMS, kotlin.math.abs(id))
            check(synchronizer.refreshInitializationMessage(key)) { "message synchronization incomplete" }
        }
    }

    private fun compile(rules: List<SenderWhitelistRuleEntity>): List<SenderWhitelistMatcher> {
        if (cachedRules != rules) {
            cachedMatchers = rules.map { SenderWhitelistMatcher(WhitelistRuleType.valueOf(it.type), it.value) }
            cachedRules = rules
        }
        return cachedMatchers
    }

    private companion object {
        const val PROJECTION_STATE = "whitelist_decision_projection"
    }
}
