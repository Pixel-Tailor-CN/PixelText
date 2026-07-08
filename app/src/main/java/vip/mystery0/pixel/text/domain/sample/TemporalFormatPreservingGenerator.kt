package vip.mystery0.pixel.text.domain.sample

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

class TemporalFormatPreservingGenerator(
    private val random: Random = Random.Default,
) {
    fun generate(type: SensitiveType, source: String): String? {
        return when (type) {
            SensitiveType.DATE -> parseDatePattern(source)?.format(randomDate())
            SensitiveType.TIME -> parseTimePattern(source)?.format(randomTime())
            SensitiveType.DATE_TIME -> parseDateTimePattern(source)?.format(randomDateTime())
            else -> null
        }
    }

    private fun parseDatePattern(source: String): DatePattern? {
        chineseDateRegex.matchEntire(source)?.let { match ->
            val yearWidth = match.groupValues[1].length
            val monthWidth = match.groupValues[2].length
            val dayWidth = match.groupValues[3].length
            if (isValidDate(match.groupValues[1], match.groupValues[2], match.groupValues[3])) {
                return ChineseDatePattern(
                    yearWidth = yearWidth,
                    monthWidth = monthWidth,
                    dayWidth = dayWidth,
                    dayUnit = match.groupValues[4],
                )
            }
        }
        chineseMonthDayRegex.matchEntire(source)?.let { match ->
            val monthWidth = match.groupValues[1].length
            val dayWidth = match.groupValues[2].length
            if (isValidMonthDay(match.groupValues[1], match.groupValues[2])) {
                return ChineseDatePattern(
                    yearWidth = null,
                    monthWidth = monthWidth,
                    dayWidth = dayWidth,
                    dayUnit = match.groupValues[3],
                )
            }
        }
        delimitedDateRegex.matchEntire(source)?.let { match ->
            val yearWidth = match.groupValues[1].length
            val monthWidth = match.groupValues[3].length
            val dayWidth = match.groupValues[4].length
            if (isValidDate(match.groupValues[1], match.groupValues[3], match.groupValues[4])) {
                return DelimitedDatePattern(
                    yearWidth = yearWidth,
                    monthWidth = monthWidth,
                    dayWidth = dayWidth,
                    separator = match.groupValues[2],
                )
            }
        }
        delimitedMonthDayRegex.matchEntire(source)?.let { match ->
            val monthWidth = match.groupValues[1].length
            val dayWidth = match.groupValues[3].length
            if (isValidMonthDay(match.groupValues[1], match.groupValues[3])) {
                return DelimitedDatePattern(
                    yearWidth = null,
                    monthWidth = monthWidth,
                    dayWidth = dayWidth,
                    separator = match.groupValues[2],
                )
            }
        }
        return null
    }

    private fun parseTimePattern(source: String): TimePattern? {
        chineseTimeRegex.matchEntire(source)?.let { match ->
            val hourWidth = match.groupValues[1].length
            val minuteWidth = match.groupValues[3].length
            val secondWidth = match.groupValues[4].takeIf(String::isNotBlank)?.length
            if (isValidTime(match.groupValues[1], match.groupValues[3], match.groupValues[4])) {
                return ChineseTimePattern(
                    hourWidth = hourWidth,
                    minuteWidth = minuteWidth,
                    secondWidth = secondWidth,
                    hourUnit = match.groupValues[2],
                )
            }
        }
        delimitedTimeRegex.matchEntire(source)?.let { match ->
            val hourWidth = match.groupValues[1].length
            val minuteWidth = match.groupValues[3].length
            val secondWidth = match.groupValues[4].takeIf(String::isNotBlank)?.length
            if (isValidTime(match.groupValues[1], match.groupValues[3], match.groupValues[4])) {
                return DelimitedTimePattern(
                    hourWidth = hourWidth,
                    minuteWidth = minuteWidth,
                    secondWidth = secondWidth,
                    separator = match.groupValues[2],
                )
            }
        }
        return null
    }

    private fun parseDateTimePattern(source: String): DateTimePattern? {
        return parseDatePrefixCandidates(source).firstNotNullOfOrNull { candidate ->
            val remainder = source.substring(candidate.consumedLength)
            separatorLengthCandidates(remainder).firstNotNullOfOrNull { separatorLength ->
                val separator = remainder.substring(0, separatorLength)
                if (separator.isEmpty() && !candidate.pattern.allowsAdjacentTime) {
                    return@firstNotNullOfOrNull null
                }
                val timeText = remainder.substring(separatorLength)
                if (timeText.isBlank()) return@firstNotNullOfOrNull null
                val timePattern = parseTimePattern(timeText) ?: return@firstNotNullOfOrNull null
                DateTimePattern(
                    datePattern = candidate.pattern,
                    separator = separator,
                    timePattern = timePattern,
                )
            }
        }
    }

    private fun parseDatePrefixCandidates(source: String): List<DatePrefixCandidate> {
        val candidates = mutableListOf<DatePrefixCandidate>()

        chineseDatePrefixRegex.find(source)?.takeIf { it.range.first == 0 }?.let { match ->
            if (isValidDate(match.groupValues[1], match.groupValues[2], match.groupValues[3])) {
                candidates += DatePrefixCandidate(
                    consumedLength = match.range.last + 1,
                    pattern = ChineseDatePattern(
                        yearWidth = match.groupValues[1].length,
                        monthWidth = match.groupValues[2].length,
                        dayWidth = match.groupValues[3].length,
                        dayUnit = match.groupValues[4],
                    )
                )
            }
        }

        chineseMonthDayPrefixRegex.find(source)?.takeIf { it.range.first == 0 }?.let { match ->
            if (isValidMonthDay(match.groupValues[1], match.groupValues[2])) {
                candidates += DatePrefixCandidate(
                    consumedLength = match.range.last + 1,
                    pattern = ChineseDatePattern(
                        yearWidth = null,
                        monthWidth = match.groupValues[1].length,
                        dayWidth = match.groupValues[2].length,
                        dayUnit = match.groupValues[3],
                    )
                )
            }
        }

        delimitedDatePrefixRegex.find(source)?.takeIf { it.range.first == 0 }?.let { match ->
            if (isValidDate(match.groupValues[1], match.groupValues[3], match.groupValues[4])) {
                candidates += DatePrefixCandidate(
                    consumedLength = match.range.last + 1,
                    pattern = DelimitedDatePattern(
                        yearWidth = match.groupValues[1].length,
                        monthWidth = match.groupValues[3].length,
                        dayWidth = match.groupValues[4].length,
                        separator = match.groupValues[2],
                    )
                )
            }
        }

        delimitedMonthDayPrefixRegex.find(source)?.takeIf { it.range.first == 0 }?.let { match ->
            if (isValidMonthDay(match.groupValues[1], match.groupValues[3])) {
                candidates += DatePrefixCandidate(
                    consumedLength = match.range.last + 1,
                    pattern = DelimitedDatePattern(
                        yearWidth = null,
                        monthWidth = match.groupValues[1].length,
                        dayWidth = match.groupValues[3].length,
                        separator = match.groupValues[2],
                    )
                )
            }
        }

        return candidates.sortedByDescending(DatePrefixCandidate::consumedLength)
    }

    private fun separatorLengthCandidates(remainder: String): IntRange {
        var maxLength = 0
        while (maxLength < remainder.length) {
            val char = remainder[maxLength]
            if (!char.isWhitespace() && char != 'T' && char != 't') {
                break
            }
            maxLength += 1
        }
        return 0..maxLength
    }

    private fun randomDate(): LocalDate {
        val start = LocalDate.of(2020, 1, 1).toEpochDay()
        val end = LocalDate.of(2035, 12, 31).toEpochDay()
        return LocalDate.ofEpochDay(random.nextLong(start, end + 1))
    }

    private fun randomTime(): LocalTime {
        return LocalTime.of(
            random.nextInt(0, 24),
            random.nextInt(0, 60),
            random.nextInt(0, 60),
        )
    }

    private fun randomDateTime(): LocalDateTime {
        return LocalDateTime.of(randomDate(), randomTime())
    }

    private fun isValidDate(
        yearText: String,
        monthText: String,
        dayText: String,
    ): Boolean {
        val year = validationYear(yearText)
        val month = monthText.toIntOrNull() ?: return false
        val day = dayText.toIntOrNull() ?: return false
        return runCatching {
            LocalDate.of(year, month, day)
        }.isSuccess
    }

    private fun isValidMonthDay(
        monthText: String,
        dayText: String,
    ): Boolean {
        val month = monthText.toIntOrNull() ?: return false
        val day = dayText.toIntOrNull() ?: return false
        return runCatching {
            LocalDate.of(2024, month, day)
        }.isSuccess
    }

    private fun isValidTime(
        hourText: String,
        minuteText: String,
        secondText: String,
    ): Boolean {
        val hour = hourText.toIntOrNull() ?: return false
        val minute = minuteText.toIntOrNull() ?: return false
        val second = secondText.takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
        return runCatching {
            LocalTime.of(hour, minute, second)
        }.isSuccess
    }

    private fun validationYear(yearText: String): Int {
        val year = yearText.toIntOrNull() ?: return 2024
        return if (yearText.length >= 4) year else 2000 + (year % 100)
    }

    private data class DatePrefixCandidate(
        val consumedLength: Int,
        val pattern: DatePattern,
    )

    private data class DateTimePattern(
        val datePattern: DatePattern,
        val separator: String,
        val timePattern: TimePattern,
    ) {
        fun format(dateTime: LocalDateTime): String {
            return datePattern.format(dateTime.toLocalDate()) +
                separator +
                timePattern.format(dateTime.toLocalTime())
        }
    }

    private sealed interface DatePattern {
        val allowsAdjacentTime: Boolean

        fun format(date: LocalDate): String
    }

    private data class ChineseDatePattern(
        val yearWidth: Int?,
        val monthWidth: Int,
        val dayWidth: Int,
        val dayUnit: String,
    ) : DatePattern {
        override val allowsAdjacentTime: Boolean = true

        override fun format(date: LocalDate): String {
            return buildString {
                if (yearWidth != null) {
                    append(formatTemporalYear(date.year, yearWidth))
                    append("年")
                }
                append(formatTemporalNumber(date.monthValue, monthWidth))
                append("月")
                append(formatTemporalNumber(date.dayOfMonth, dayWidth))
                append(dayUnit)
            }
        }
    }

    private data class DelimitedDatePattern(
        val yearWidth: Int?,
        val monthWidth: Int,
        val dayWidth: Int,
        val separator: String,
    ) : DatePattern {
        override val allowsAdjacentTime: Boolean = false

        override fun format(date: LocalDate): String {
            return buildString {
                if (yearWidth != null) {
                    append(formatTemporalYear(date.year, yearWidth))
                    append(separator)
                }
                append(formatTemporalNumber(date.monthValue, monthWidth))
                append(separator)
                append(formatTemporalNumber(date.dayOfMonth, dayWidth))
            }
        }
    }

    private sealed interface TimePattern {
        fun format(time: LocalTime): String
    }

    private data class ChineseTimePattern(
        val hourWidth: Int,
        val minuteWidth: Int,
        val secondWidth: Int?,
        val hourUnit: String,
    ) : TimePattern {
        override fun format(time: LocalTime): String {
            return buildString {
                append(formatTemporalNumber(time.hour, hourWidth))
                append(hourUnit)
                append(formatTemporalNumber(time.minute, minuteWidth))
                append("分")
                if (secondWidth != null) {
                    append(formatTemporalNumber(time.second, secondWidth))
                    append("秒")
                }
            }
        }
    }

    private data class DelimitedTimePattern(
        val hourWidth: Int,
        val minuteWidth: Int,
        val secondWidth: Int?,
        val separator: String,
    ) : TimePattern {
        override fun format(time: LocalTime): String {
            return buildString {
                append(formatTemporalNumber(time.hour, hourWidth))
                append(separator)
                append(formatTemporalNumber(time.minute, minuteWidth))
                if (secondWidth != null) {
                    append(separator)
                    append(formatTemporalNumber(time.second, secondWidth))
                }
            }
        }
    }

    private companion object {
        private val chineseDateRegex = Regex("""^(\d{2,4})年(\d{1,2})月(\d{1,2})(日|号)$""")
        private val chineseMonthDayRegex = Regex("""^(\d{1,2})月(\d{1,2})(日|号)$""")
        private val delimitedDateRegex = Regex("""^(\d{2,4})([-/.])(\d{1,2})\2(\d{1,2})$""")
        private val delimitedMonthDayRegex = Regex("""^(\d{1,2})([-/.])(\d{1,2})$""")
        private val chineseTimeRegex = Regex("""^(\d{1,2})(时|点)(\d{1,2})分(?:(\d{1,2})秒)?$""")
        private val delimitedTimeRegex = Regex("""^(\d{1,2})([:：])(\d{1,2})(?:\2(\d{1,2}))?$""")

        private val chineseDatePrefixRegex = Regex("""^(\d{2,4})年(\d{1,2})月(\d{1,2})(日|号)""")
        private val chineseMonthDayPrefixRegex = Regex("""^(\d{1,2})月(\d{1,2})(日|号)""")
        private val delimitedDatePrefixRegex = Regex("""^(\d{2,4})([-/.])(\d{1,2})\2(\d{1,2})""")
        private val delimitedMonthDayPrefixRegex = Regex("""^(\d{1,2})([-/.])(\d{1,2})""")
    }
}

private fun formatTemporalNumber(value: Int, width: Int): String {
    return value.toString().padStart(width, '0')
}

private fun formatTemporalYear(year: Int, width: Int): String {
    return year.toString().takeLast(width).padStart(width, '0')
}
