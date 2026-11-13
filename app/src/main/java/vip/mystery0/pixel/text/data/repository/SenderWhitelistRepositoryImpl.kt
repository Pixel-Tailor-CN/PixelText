package vip.mystery0.pixel.text.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.db.SenderWhitelistRuleEntity
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamLegacyImporter
import vip.mystery0.pixel.text.data.db.mirror.MirrorSyncStateEntity
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.data.source.WhitelistMessageSource
import vip.mystery0.pixel.text.data.source.whitelistMessageFingerprint
import vip.mystery0.pixel.text.data.source.mirror.ProviderRowSnapshot
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistMatcher
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRule
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType
import vip.mystery0.pixel.text.domain.spam.MessageSpamSource
import vip.mystery0.pixel.text.util.AppStrings

class SenderWhitelistRepositoryImpl(
    private val mirror: MessageMirrorDatabase,
    private val source: WhitelistMessageSource,
    private val importer: MirrorSpamLegacyImporter,
    private val synchronizer: MessageMirrorSynchronizer,
) : SenderWhitelistRepository {
    private val dao = mirror.senderWhitelistDao()
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
        require(values.isNotEmpty()) { AppStrings.get(R.string.domain_whitelist_error_empty) }
        require(id == null || values.size == 1) { AppStrings.get(R.string.domain_whitelist_error_edit_single) }
        values.forEach { value -> SenderWhitelistMatcher.validate(type, value)?.let { error(it) } }
        decisionMutex.withLock {
            val current = dao.getRules()
            require(id == null || current.any { it.id == id }) { AppStrings.get(R.string.domain_whitelist_error_not_found) }
            require(current.count { it.id != id } + values.size <= SenderWhitelistMatcher.MAX_RULES) {
                AppStrings.get(R.string.domain_whitelist_error_max_rules, SenderWhitelistMatcher.MAX_RULES)
            }
            require(values.none { value -> current.any { it.id != id && it.type == type.name && it.value == value } }) {
                AppStrings.get(R.string.domain_whitelist_error_duplicate)
            }
            val now = System.currentTimeMillis()
            val additions = values.map { SenderWhitelistRuleEntity(id ?: 0, type.name, it, now) }
            updateRules(current, current.filterNot { it.id == id } + additions) {
                if (id != null) dao.deleteRule(id)
                dao.insertRules(additions)
            }
        }
    }

    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        importer.ensureImported()
        decisionMutex.withLock {
            val current = dao.getRules()
            require(current.any { it.id == id }) { AppStrings.get(R.string.domain_whitelist_error_not_found) }
            updateRules(current, current.filterNot { it.id == id }) {
                dao.deleteRule(id)
            }
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
            val current = dao.getRules()
            val keys = current.map { it.type to it.value }.toSet()
            val additions = rules.distinctBy { it.type to it.value }.filter { (it.type to it.value) !in keys }
            require(current.size + additions.size <= SenderWhitelistMatcher.MAX_RULES) {
                AppStrings.get(R.string.domain_whitelist_error_merge_limit, SenderWhitelistMatcher.MAX_RULES)
            }
            updateRules(current, current + additions, rebuildKeywords = true) {
                val keywordDao = mirror.blockedKeywordDao()
                keywords.forEach { row ->
                    if (keywordDao.getByNormalizedKeyword(row.normalizedKeyword) == null) keywordDao.insert(row.copy(id = 0))
                }
                if (additions.isNotEmpty()) dao.insertRules(additions.map { it.copy(id = 0) })
            }
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

    /** 只恢复旧版跨库提交遗留的标记，新版规则修改不会产生中间状态。 */
    suspend fun reconcileMessageWhitelistIfNeeded() = decisionMutex.withLock {
        if (projectionChecked) return@withLock
        if (mirror.syncDao().state(PROJECTION_STATE)?.complete == false) {
            val rules = dao.getRules()
            updateRules(rules, rules) {}
        }
        projectionChecked = true
    }

    /** 同步仅处理缺失或身份变化；规则与全部受影响消息在一个事务内生效。 */
    private suspend fun updateRules(
        previous: List<SenderWhitelistRuleEntity>,
        next: List<SenderWhitelistRuleEntity>,
        rebuildKeywords: Boolean = false,
        mutation: suspend () -> Unit,
    ) {
        val oldMatchers = compile(previous)
        val newMatchers = compile(next)
        val history = source.load()
        val candidates = history.filter { message ->
            oldMatchers.any { it.matches(message.sender) } || newMatchers.any { it.matches(message.sender) }
        }.map { it.id }
        val previouslyAllowed = decisions.whitelistMessageIds()
        val affected = (candidates + previouslyAllowed).distinct()
        ensureMirrored(affected)
        MirrorSynchronizationLock.mutex.withLock {
            val rows = decisions.rows(affected)
            val fingerprints = mirroredFingerprints(affected)
            // 同步期间 Provider 可能改变身份，使用当前身份重新匹配，防止沿用旧消息权限。
            val current = source.load(affected).filter { it.fingerprint == fingerprints[it.id] }
            val validIds = current.mapTo(mutableSetOf()) { it.id }
            val versions = rows.filter { it.messageId in validIds }
                .associate { it.messageId to (it.localId to it.messageRevision) }
            val oldMatches = current.filter { message -> oldMatchers.any { it.matches(message.sender) } }.map { it.id }
            val newMatches = current.filter { message -> newMatchers.any { it.matches(message.sender) } }.map { it.id }
            val stillAllowed = rows
                .filter { it.spamAllowedSource == MessageSpamSource.WHITELIST }.map { it.messageId }
            val released = (oldMatches + stillAllowed).toSet() - newMatches.toSet()
            mirror.withTransaction {
                mutation()
                decisions.allow(newMatches, expectedVersions = versions)
                // 删除或修改规则只将历史命中转为永久放行，不撤销它。
                decisions.allow(released, MessageSpamSource.RELEASED, versions)
                if (rebuildKeywords) rebuildKeywordProjection(mirror)
                mirror.syncDao().putState(MirrorSyncStateEntity(PROJECTION_STATE, complete = true))
            }
        }
        projectionChecked = true
    }

    private suspend fun ensureMirrored(ids: Collection<Long>) {
        val requested = ids.distinct().filter { it != 0L && it != Long.MIN_VALUE }
        if (requested.isEmpty()) return
        val changed = if (requested.size == 1) requested else {
            val current = source.load(requested).associate { it.id to it.fingerprint }
            val mirrored = mirroredFingerprints(requested)
            // 批量身份比较只读数据库；只有缺失、删除或身份变化才触发镜像更新。
            requested.filter { current[it] != mirrored[it] }
        }
        for (id in changed) {
            val key = SourceMessageKey(if (id > 0) MessageTransport.SMS else MessageTransport.MMS, kotlin.math.abs(id))
            check(synchronizer.refreshInitializationMessage(key)) { "message synchronization incomplete" }
        }
    }

    private suspend fun mirroredFingerprints(ids: Collection<Long>): Map<Long, String> = buildMap {
        ids.filter { it > 0 }.chunked(500).forEach { batch ->
            mirror.mirrorDao().conversationSmsByIds(batch).forEach { row ->
                put(row.sourceId, whitelistMessageFingerprint(row.sourceId, row.address.orEmpty(),
                    row.originalDate ?: 0L, row.dateSent ?: 0L))
            }
        }
        ids.filter { it < 0 && it != Long.MIN_VALUE }.map { -it }.chunked(500).forEach { batch ->
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
