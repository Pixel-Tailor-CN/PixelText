package vip.mystery0.pixel.text.domain.sample

data class DesensitizedDraft(val text: String, val sender: String)

/** 只处理上传草稿；按原始 UTF-16 范围去重，避免重叠数字和补充平面字符错位。 */
class AutomaticSampleDesensitizer {
    private data class Replacement(val start: Int, val end: Int, val value: String)

    fun prepare(text: String, sender: String): DesensitizedDraft =
        DesensitizedDraft(redact(text), redact(sender))

    private fun redact(text: String): String {
        val replacements = mutableListOf<Replacement>()
        fun add(start: Int, end: Int, value: String) {
            if (replacements.none { start < it.end && end > it.start }) {
                replacements += Replacement(start, end, value)
            }
        }
        // 先处理链接参数，其范围不会再被手机号等规则拆分。
        Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE).findAll(text).forEach { url ->
            Regex("""([?&#][^=&#\s]+=)([^&#\s]+)""").findAll(url.value).forEach { parameter ->
                val value = parameter.groups[2]!!
                add(url.range.first + value.range.first, url.range.first + value.range.last + 1, "REDACTED")
            }
        }
        val rules = listOf(
            Regex("""(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])""") to "[证件号]",
            Regex("""(?<![0-9])(?:\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])""") to "[手机号]",
            Regex("""(?<![0-9])[0-9]{12,19}(?![0-9])""") to "[账户号]",
        )
        rules.forEach { (regex, replacement) ->
            regex.findAll(text).forEach { add(it.range.first, it.range.last + 1, replacement) }
        }
        Regex("""(?:验证码|校验码|动态码|口令|OTP|code)[：:为是\s]*([0-9]{4,8})(?![0-9])""",
            RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val value = match.groups[1]!!
            add(value.range.first, value.range.last + 1, "[验证码]")
        }
        Regex("""(?:账号|账户|卡号|尾号)[：:为是\s]*([0-9]{4,19})(?![0-9])""").findAll(text).forEach { match ->
            val value = match.groups[1]!!
            add(value.range.first, value.range.last + 1, "[账户号]")
        }
        return StringBuilder(text).apply {
            replacements.sortedByDescending { it.start }.forEach { replace(it.start, it.end, it.value) }
        }.toString()
    }
}
