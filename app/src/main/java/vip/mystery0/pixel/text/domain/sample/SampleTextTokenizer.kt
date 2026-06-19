package vip.mystery0.pixel.text.domain.sample

data class SampleTextToken(
    val text: String,
    val start: Int,
    val end: Int,
    val selectable: Boolean,
    val kind: SampleTokenKind,
)

enum class SampleTokenKind {
    CHINESE,
    NUMBER,
    ALPHA_NUMERIC,
    SEPARATOR,
}

class SampleTextTokenizer {
    fun tokenize(content: String): List<SampleTextToken> {
        if (content.isEmpty()) return emptyList()
        val tokens = mutableListOf<SampleTextToken>()
        var index = 0
        while (index < content.length) {
            val char = content[index]
            val kind = char.kind()
            val start = index
            index = when (kind) {
                SampleTokenKind.CHINESE -> consumeWhile(content, index) { it.isChinese() }
                SampleTokenKind.NUMBER -> consumeWhile(content, index) { it.isAsciiDigit() }
                SampleTokenKind.ALPHA_NUMERIC -> consumeWhile(content, index) {
                    it.isAsciiLetter() || it.isAsciiDigit()
                }

                SampleTokenKind.SEPARATOR -> index + 1
            }
            tokens += SampleTextToken(
                text = content.substring(start, index),
                start = start,
                end = index,
                selectable = kind != SampleTokenKind.SEPARATOR,
                kind = kind
            )
        }
        return tokens
    }

    fun selectionUnits(content: String): List<SampleTextToken> {
        return tokenize(content).flatMap { token ->
            if (token.kind != SampleTokenKind.CHINESE) {
                listOf(token)
            } else {
                token.text.mapIndexed { index, char ->
                    val start = token.start + index
                    SampleTextToken(
                        text = char.toString(),
                        start = start,
                        end = start + 1,
                        selectable = true,
                        kind = SampleTokenKind.CHINESE
                    )
                }
            }
        }
    }

    private fun consumeWhile(
        content: String,
        start: Int,
        predicate: (Char) -> Boolean,
    ): Int {
        var index = start
        while (index < content.length && predicate(content[index])) {
            index += 1
        }
        return index
    }

    private fun Char.kind(): SampleTokenKind = when {
        isChinese() -> SampleTokenKind.CHINESE
        isAsciiDigit() -> SampleTokenKind.NUMBER
        isAsciiLetter() -> SampleTokenKind.ALPHA_NUMERIC
        else -> SampleTokenKind.SEPARATOR
    }
}

internal fun Char.isChinese(): Boolean = this in '\u4e00'..'\u9fff'

internal fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

internal fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'
