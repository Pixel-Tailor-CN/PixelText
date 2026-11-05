package vip.mystery0.pixel.text.data.repository.mirror

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.source.whitelistMessageFingerprint
import vip.mystery0.pixel.text.domain.model.mirror.ConversationMessageSnapshot
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistMatcher
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType

/** 列表只读筛选：身份来自镜像，不查询 Provider，也不顺便持久化白名单。 */
class LocalConversationSpamFilter(private val database: SpamDatabase,
    private val decisions: vip.mystery0.pixel.text.domain.spam.SpamRepository) {
    suspend fun spamIds(messages: List<ConversationMessageSnapshot>, threshold: Float): Set<Long> {
        val started = android.os.SystemClock.elapsedRealtime()
        val whitelist = database.senderWhitelistDao()
        val matchers = whitelist.getRules().map {
            SenderWhitelistMatcher(WhitelistRuleType.valueOf(it.type), it.value)
        }
        val keys = mutableListOf<vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey>()
        for (batch in messages.chunked(500)) {
            currentCoroutineContext().ensureActive()
            val ids = batch.map { it.message.id }
            val allowed = whitelist.getAllowed(ids).associateBy { it.messageId }
            for (snapshot in batch) {
                currentCoroutineContext().ensureActive()
                val messageId = snapshot.message.id
                val sender = snapshot.whitelistSender
                val date = snapshot.originalDate
                val sent = snapshot.dateSent
                // 没有持久放行记录时无需计算白名单指纹，规则匹配仍照常执行。
                val allowedByIdentity = allowed[messageId]?.let {
                    it.fingerprint == whitelistMessageFingerprint(messageId, sender, date, sent)
                } == true
                if (allowedByIdentity || matchers.any {
                        it.matches(
                            sender
                        )
                    }) continue
                val visibleMessage = snapshot.message
                val key = vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey.create(
                    if (visibleMessage.isMms) "MMS" else "SMS", messageId, visibleMessage.sender, visibleMessage.timestamp, visibleMessage.content)
                keys += key
            }
        }
        val preparedAt = android.os.SystemClock.elapsedRealtime()
        val result = decisions.scoresForKeys(keys).filterValues { it >= threshold }.keys
        if (vip.mystery0.pixel.text.BuildConfig.DEBUG) android.util.Log.d(
            "ConversationFilter",
            "conversation filter completed count=${messages.size} preparation_ms=${preparedAt - started} scores_ms=${android.os.SystemClock.elapsedRealtime() - preparedAt}",
        )
        return result
    }
}
