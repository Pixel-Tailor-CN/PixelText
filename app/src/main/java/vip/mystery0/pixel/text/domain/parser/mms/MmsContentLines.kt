package vip.mystery0.pixel.text.domain.parser.mms

/** 先还原逻辑行，再识别结构；QP 只在属性明确声明该编码时启用软换行。 */
internal fun unfoldMmsContentLines(
    text: String,
    quotedPrintable: Boolean = false,
    checkCancelled: () -> Unit = {},
): Sequence<String> = sequence {
    require(text.length <= 512 * 1024)
    var current = StringBuilder()
    var active = false
    var headerEnd = false
    var inQuotes = false
    var scanned = 0
    var qp = false
    for (physical in text.lineSequence()) {
        checkCancelled()
        val softBreak = active && qp && current.lastOrNull() == '='
        val folded = active && (physical.startsWith(' ') || physical.startsWith('\t'))
        if (!softBreak && !folded) {
            if (active) yield(current.toString())
            current = StringBuilder()
            active = true
            headerEnd = false
            inQuotes = false
            scanned = 0
            qp = false
        }
        when {
            // QP 软换行的下一行是值内容，行首空白也属于值。
            softBreak -> { current.setLength(current.length - 1); current.append(physical) }
            folded -> current.append(physical, 1, physical.length)
            else -> current.append(physical)
        }
        if (quotedPrintable && !headerEnd) {
            while (scanned < current.length) {
                if (scanned % 4096 == 0) checkCancelled()
                val char = current[scanned++]
                if (char == '"') inQuotes = !inQuotes
                if (char == ':' && !inQuotes) {
                    headerEnd = true
                    qp = hasQuotedPrintableParameter(current.substring(0, scanned - 1))
                    break
                }
            }
        }
    }
    checkCancelled()
    if (active) yield(current.toString())
}

private fun hasQuotedPrintableParameter(header: String): Boolean {
    var inQuotes = false
    var start = 0
    for (index in 0..header.length) {
        if (index < header.length && header[index] == '"') inQuotes = !inQuotes
        if (index == header.length || (header[index] == ';' && !inQuotes)) {
            val parameter = header.substring(start, index)
            if (parameter.substringBefore('=').equals("ENCODING", true) &&
                parameter.substringAfter('=', "").removeSurrounding("\"").equals("QUOTED-PRINTABLE", true)) return true
            start = index + 1
        }
    }
    return false
}
