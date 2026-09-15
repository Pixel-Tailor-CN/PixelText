package vip.mystery0.pixel.text.domain.spam

import com.google.re2j.Pattern
import kotlinx.coroutines.flow.Flow

enum class WhitelistRuleType(val label: String) {
    EXACT("精确号码"), REGEX("正则表达式")
}

data class SenderWhitelistRule(
    val id: Long,
    val type: WhitelistRuleType,
    val value: String,
)

/** 预览和实际收信共用同一编译器，正则仅对原始号码进行整串匹配。 */
class SenderWhitelistMatcher(type: WhitelistRuleType, private val value: String) {
    private val pattern = if (type == WhitelistRuleType.REGEX) Pattern.compile(value) else null
    fun matches(sender: String): Boolean = sender.isNotBlank() &&
        (pattern?.matcher(sender)?.matches() ?: (sender == value))

    companion object {
        const val MAX_RULE_LENGTH = 256
        const val MAX_RULES = 500
        fun validate(type: WhitelistRuleType, value: String): String? {
            if (value.isBlank()) return "请输入号码或表达式"
            if (value.length > MAX_RULE_LENGTH) return "规则不能超过 $MAX_RULE_LENGTH 个字符"
            if (type == WhitelistRuleType.EXACT && !Regex("\\+?[0-9]{1,32}").matches(value)) {
                return "精确号码仅支持数字和开头的 +，每行一个号码"
            }
            return try {
                val matcher = SenderWhitelistMatcher(type, value)
                if (type == WhitelistRuleType.REGEX && matcher.pattern!!.matcher("").matches()) {
                    "表达式不能匹配空号码，请缩小匹配范围"
                } else null
            } catch (_: com.google.re2j.PatternSyntaxException) {
                "正则表达式无效，或使用了不支持的环视、回溯引用"
            }
        }
    }
}

interface SenderWhitelistRepository {
    fun observeRules(): Flow<List<SenderWhitelistRule>>
    suspend fun save(id: Long?, type: WhitelistRuleType, input: String)
    suspend fun delete(id: Long)
    suspend fun allowedMessageIds(messageIds: Collection<Long>): Set<Long>
    suspend fun isAllowed(messageId: Long): Boolean = messageId in allowedMessageIds(listOf(messageId))
    /** 与规则修改串行化；自动已读、删除和通知只能在最终放行判定后执行。 */
    suspend fun <T> withMessageDecision(messageId: Long, action: suspend (allowed: Boolean) -> T): T
    suspend fun forget(messageIds: Collection<Long>)
}
