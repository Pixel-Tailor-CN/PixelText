package vip.mystery0.pixel.text.domain.parser.mms

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import vip.mystery0.pixel.text.domain.model.mms.MmsCalendarModel

/** 有界的 VEVENT 只读解析器；不访问 URL，不展开重复事件或自定义时区规则。 */
class MmsCalendarParser {
    private data class Property(val name: String, val parameters: Map<String, String>, val value: String)
    private data class EventTime(val value: ZonedDateTime, val allDay: Boolean, val floating: Boolean)

    fun parse(text: String, checkCancelled: () -> Unit = {}): List<MmsCalendarModel> {
        if (text.length > 512 * 1024) return listOf(failed("日历过大，请打开原件"))
        val unfolded = unfoldMmsContentLines(text, checkCancelled = checkCancelled).toList()
        val result = mutableListOf<MmsCalendarModel>()
        val hasTimezoneDefinition = unfolded.any { it.equals("BEGIN:VTIMEZONE", true) }
        var properties: MutableList<Property>? = null
        var nested = 0
        var incomplete = false
        for (line in unfolded) {
            checkCancelled()
            val property = property(line)
            if (property == null) {
                if (properties != null && line.isNotBlank()) incomplete = true
                continue
            }
            if (property.name == "BEGIN" && property.value.equals("VEVENT", true)) {
                if (properties == null && result.size == 32) {
                    val last = result.last()
                    result[result.lastIndex] = last.copy(importWarning = listOfNotNull(last.importWarning,
                        "最多展示 32 个事件，其余事件请打开原件").joinToString("；"))
                    break
                }
                if (properties != null) incomplete = true
                else { properties = mutableListOf(); nested = 0; incomplete = false }
            } else if (properties != null) {
                when {
                    property.name == "END" && property.value.equals("VEVENT", true) -> {
                        result += parseEvent(properties, incomplete, hasTimezoneDefinition)
                        properties = null
                    }
                    property.name == "BEGIN" -> { nested++; incomplete = true }
                    property.name == "END" -> { nested--; incomplete = true }
                    nested == 0 && properties.size < 512 -> properties += property
                    else -> incomplete = true
                }
            }
        }
        if (properties != null) result += failed("日历事件不完整，请打开原件")
        return result.ifEmpty { listOf(failed("无法读取日历事件，请打开原件")) }
    }

    private fun parseEvent(properties: List<Property>, incomplete: Boolean, hasTimezoneDefinition: Boolean): MmsCalendarModel {
        val warnings = mutableListOf<String>()
        if (incomplete) warnings += "附加字段或提醒未完整导入"
        fun first(name: String) = properties.firstOrNull { it.name == name }
        fun field(name: String) = first(name)?.value?.let(::unescape)?.takeIf(String::isNotBlank)?.also {
            if (it.length > 4096) warnings += "过长字段已截短"
        }?.take(4096)
        val title = field("SUMMARY")
        val location = field("LOCATION")
        val description = field("DESCRIPTION")
        val recurrence = field("RRULE")
        val recurring = properties.any { it.name in setOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID") }
        if (recurring) warnings += "系统添加仅支持本次事件；完整重复规则及例外请打开原始 ICS"
        if (properties.any { it.name !in SUPPORTED }) warnings += "部分日历属性请核对原件"
        if (title == null) warnings += "事件缺少标题"
        var start: EventTime? = null
        var end: Instant? = null
        var valid = true
        try {
            // 自定义规则可能覆盖同名 IANA 时区；不以设备规则静默替代发送方规则。
            if (hasTimezoneDefinition && properties.any { it.name in setOf("DTSTART", "DTEND") && "TZID" in it.parameters }) {
                warnings += "内嵌时区规则需由日历应用完整读取"
                error("embedded timezone definition")
            }
            if (listOf("DTSTART", "DTEND", "DURATION").any { name -> properties.count { it.name == name } > 1 }) {
                error("ambiguous event time")
            }
            start = first("DTSTART")?.let(::time) ?: error("missing start")
            if (start.floating) warnings += "未声明时区，按设备当前时区显示，请核对"
            val endProperty = first("DTEND")
            val duration = first("DURATION")
            if (endProperty != null && duration != null) error("conflicting end")
            end = when {
                endProperty != null -> time(endProperty).also {
                    if (it.allDay != start.allDay) error("inconsistent time type")
                }.value.toInstant()
                duration != null -> addDuration(start, duration.value).toInstant()
                start.allDay -> start.value.plusDays(1).toInstant()
                else -> start.value.toInstant()
            }
            if (end < start.value.toInstant() || (start.allDay && end == start.value.toInstant())) error("invalid end")
            // 系统日历使用 Long 毫秒，java.time 能表示的日期范围更大。
            start.value.toInstant().toEpochMilli()
            end.toEpochMilli()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            valid = false
            end = null
            warnings += "日期、时区或时长无法可靠读取，请打开原件"
        }
        return MmsCalendarModel(title, start?.value?.toInstant(), end, start?.value?.zone?.id, start?.allDay ?: false,
            location, description, recurrence, warnings.distinct().takeIf { it.isNotEmpty() }?.joinToString("；"), valid)
    }

    private fun time(property: Property): EventTime {
        val allDay = property.parameters["VALUE"]?.equals("DATE", true) == true || property.value.matches(Regex("\\d{8}"))
        if (allDay) {
            if (property.parameters.containsKey("TZID")) error("date with timezone")
            return EventTime(LocalDate.parse(property.value, DATE).atStartOfDay(ZoneOffset.UTC), true, false)
        }
        val utc = property.value.endsWith('Z')
        val tz = property.parameters["TZID"]
        if (utc && tz != null) error("utc with timezone")
        val zone = if (utc) ZoneOffset.UTC else tz?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val local = LocalDateTime.parse(property.value.removeSuffix("Z"), DATE_TIME)
        // 夏令时跳跃与回拨的歧义时间不静默改写。
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1) error("ambiguous local time")
        return EventTime(ZonedDateTime.ofStrict(local, offsets.single(), zone), false, !utc && tz == null)
    }

    private fun addDuration(start: EventTime, value: String): ZonedDateTime {
        val match = DURATION.matchEntire(value) ?: error("invalid duration")
        fun amount(index: Int): Long = match.groupValues[index].let {
            if (it.isEmpty()) 0L else it.toLongOrNull() ?: error("duration amount overflow")
        }
        val weeks = amount(1)
        val days = amount(2)
        val time = match.groupValues[3]
        if (weeks == 0L && days == 0L && time.isEmpty()) error("empty duration")
        if (start.allDay && time.isNotEmpty()) error("date duration contains time")
        return start.value.plusWeeks(weeks).plusDays(days).let {
            if (time.isEmpty()) it else it.plus(Duration.parse("P$time"))
        }
    }

    private fun property(line: String): Property? {
        var quoted = false
        val tokens = mutableListOf<String>()
        var begin = 0
        for (index in line.indices) {
            when (line[index]) {
                '"' -> quoted = !quoted
                ';' -> if (!quoted) { tokens += line.substring(begin, index); begin = index + 1 }
                ':' -> if (!quoted) {
                    tokens += line.substring(begin, index)
                    if (tokens.first().isBlank()) return null
                    val parameters = tokens.drop(1).associate {
                        it.substringBefore('=').uppercase(Locale.ROOT) to it.substringAfter('=', "").removeSurrounding("\"")
                    }
                    return Property(tokens.first().uppercase(Locale.ROOT), parameters, line.substring(index + 1))
                }
            }
        }
        return null
    }

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char == '\\' && index < value.length) {
                val next = value[index++]
                append(if (next == 'n' || next == 'N') '\n' else next)
            } else append(char)
        }
    }

    private fun failed(warning: String) = MmsCalendarModel(null, null, null, null, false, null, null, null, warning, false)

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT)
        val DURATION = Regex("\\+?P(?:(\\d+)W|(?:(\\d+)D)?(T(?:\\d+H)?(?:\\d+M)?(?:\\d+S)?)?)")
        val SUPPORTED = setOf("SUMMARY", "DTSTART", "DTEND", "DURATION", "LOCATION", "DESCRIPTION", "RRULE", "UID", "DTSTAMP", "CREATED", "LAST-MODIFIED", "SEQUENCE")
    }
}
