package vip.mystery0.pixel.text.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.SenderWhitelistRuleEntity
import vip.mystery0.pixel.text.data.db.SpamAllowedMessageEntity
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.source.WhitelistMessageIdentity
import vip.mystery0.pixel.text.data.source.WhitelistMessageSource
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistMatcher
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRule
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType

class SenderWhitelistRepositoryImpl(
    private val db: SpamDatabase,
    private val source: WhitelistMessageSource,
) : SenderWhitelistRepository {
    private val dao = db.senderWhitelistDao()
    private val decisionMutex = Mutex()
    private var cachedRules = emptyList<SenderWhitelistRuleEntity>()
    private var cachedMatchers = emptyList<SenderWhitelistMatcher>()

    override fun observeRules() = dao.observeRules().map { rows ->
        rows.map { SenderWhitelistRule(it.id, WhitelistRuleType.valueOf(it.type), it.value) }
    }

    override suspend fun save(id: Long?, type: WhitelistRuleType, input: String) = withContext(Dispatchers.IO) {
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
            val matchers = compile(current) + additions.map { SenderWhitelistMatcher(type, it.value) }
            val history = source.load()
            db.withTransaction {
                // 编辑前先将旧规则和新规则覆盖的既有消息持久放行，不能随规则变化撤销。
                persistMatches(history, matchers)
                if (id != null) dao.deleteRule(id)
                dao.insertRules(additions)
            }
        }
    }

    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        decisionMutex.withLock {
            val current = dao.getRules()
            require(current.any { it.id == id }) { "规则不存在，请刷新后重试" }
            val history = source.load()
            db.withTransaction {
                // 也涵盖已入 Provider、尚在等待后台检测的新消息。
                persistMatches(history, compile(current))
                dao.deleteRule(id)
            }
        }
    }

    override suspend fun allowedMessageIds(messageIds: Collection<Long>): Set<Long> = withContext(Dispatchers.IO) {
        decisionMutex.withLock { allowedLocked(messageIds) }
    }

    override suspend fun <T> withMessageDecision(messageId: Long, action: suspend (Boolean) -> T): T =
        withContext(Dispatchers.IO) {
            decisionMutex.withLock {
                action(messageId in allowedLocked(listOf(messageId)))
            }
        }

    // 删除消息的回调可能已经持有 decisionMutex；此处只清理消息记录，不递归获取锁。
    override suspend fun forget(messageIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val existing = messageIds.chunked(800).flatMap { dao.getAllowed(it) }
        if (existing.isEmpty()) return@withContext
        val current = source.load(existing.map { it.messageId }).associateBy { it.id }
        existing.forEach { record ->
            // 镜像删除回调可能晚于 Provider ID 复用，不能删除新消息已经写入的放行记录。
            if (current[record.messageId]?.fingerprint != record.fingerprint) {
                dao.forgetIdentity(record.messageId, record.fingerprint)
            }
        }
    }

    private suspend fun allowedLocked(ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val existing = ids.chunked(800).flatMap { dao.getAllowed(it) }.associateBy { it.messageId }
        val rules = dao.getRules()
        if (existing.isEmpty() && rules.isEmpty()) return emptySet()
        val messages = source.load(ids)
        val byId = messages.associateBy { it.id }
        val stale = existing.values.filter { byId[it.messageId]?.fingerprint != it.fingerprint }.map { it.messageId }
        val matchers = compile(rules)
        val allowed = messages.filter { message ->
            existing[message.id]?.fingerprint == message.fingerprint || matchers.any { it.matches(message.sender) }
        }
        db.withTransaction {
            stale.chunked(800).forEach { dao.forget(it) }
            val now = System.currentTimeMillis()
            val additions = allowed.filter { existing[it.id]?.fingerprint != it.fingerprint }
                .map { SpamAllowedMessageEntity(it.id, it.fingerprint, now) }
            // 不重写相同记录，避免 Room 观察者被自触发成刷新循环。
            if (additions.isNotEmpty()) dao.allow(additions)
        }
        return allowed.mapTo(mutableSetOf()) { it.id }
    }

    private suspend fun persistMatches(messages: List<WhitelistMessageIdentity>, matchers: List<SenderWhitelistMatcher>) {
        if (matchers.isEmpty()) return
        val matched = messages.filter { message -> matchers.any { it.matches(message.sender) } }
        val now = System.currentTimeMillis()
        matched.chunked(800).forEach { chunk ->
            val existing = dao.getAllowed(chunk.map { it.id }).associateBy { it.messageId }
            val additions = chunk.filter { existing[it.id]?.fingerprint != it.fingerprint }
                .map { SpamAllowedMessageEntity(it.id, it.fingerprint, now) }
            if (additions.isNotEmpty()) dao.allow(additions)
        }
    }

    private fun compile(rules: List<SenderWhitelistRuleEntity>): List<SenderWhitelistMatcher> {
        if (cachedRules != rules) {
            cachedMatchers = rules.map { SenderWhitelistMatcher(WhitelistRuleType.valueOf(it.type), it.value) }
            cachedRules = rules
        }
        return cachedMatchers
    }
}
