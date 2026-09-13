package vip.mystery0.pixel.text.domain.model.search

/** 入库、迁移和输入共用；不把字母转换成电话键盘数字。 */
object SearchPhoneNumbers {
    fun digits(value: String?): String = buildString {
        value.orEmpty().forEach { char -> char.digitToIntOrNull()?.let { append(it) } }
    }

    fun queryAliases(value: String): Set<String> {
        val number = digits(value)
        if (number.isEmpty()) return emptySet()
        val formatted = value.filterNot { it.isWhitespace() || it in "()-" }
        val domestic = when {
            formatted.startsWith("+86") && number.startsWith("86") -> number.drop(2)
            formatted.startsWith("0086") && number.startsWith("0086") -> number.drop(4)
            number.length == 13 && number.startsWith("861") && number[3] in '3'..'9' -> number.drop(2)
            else -> null
        }
        return setOfNotNull(number, domestic?.takeIf { it.isNotEmpty() })
    }
}
