package vip.mystery0.pixel.text.domain.spam

import androidx.annotation.StringRes
import com.google.re2j.Pattern
import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.util.AppStrings

enum class WhitelistRuleType(@param:StringRes val labelRes: Int) {
    EXACT(R.string.domain_whitelist_rule_type_exact),
    REGEX(R.string.domain_whitelist_rule_type_regex);

    val label: String get() = AppStrings.get(labelRes)
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
            if (value.isBlank()) return AppStrings.get(R.string.domain_whitelist_error_empty)
            if (value.length > MAX_RULE_LENGTH) {
                return AppStrings.get(R.string.domain_whitelist_error_too_long, MAX_RULE_LENGTH)
            }
            if (type == WhitelistRuleType.EXACT && !Regex("\\+?[0-9]{1,32}").matches(value)) {
                return AppStrings.get(R.string.domain_whitelist_error_exact_format)
            }
            return try {
                val matcher = SenderWhitelistMatcher(type, value)
                if (type == WhitelistRuleType.REGEX && matcher.pattern!!.matcher("").matches()) {
                    AppStrings.get(R.string.domain_whitelist_error_regex_empty_match)
                } else null
            } catch (_: com.google.re2j.PatternSyntaxException) {
                AppStrings.get(R.string.domain_whitelist_error_regex_invalid)
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
