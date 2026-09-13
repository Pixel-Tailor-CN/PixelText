package vip.mystery0.pixel.text.domain.model.search

import java.time.ZonedDateTime
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport

// 日期是日历意义上的“之前”，不是最近若干毫秒。
enum class SearchDate(val label: String) {
    ANY("任意时间"), WEEK("一周前"), MONTH("一月前"), HALF_YEAR("半年前"), YEAR("一年前");

    fun cutoff(now: ZonedDateTime): Long? = when (this) {
        ANY -> null
        WEEK -> now.minusDays(7)
        MONTH -> now.minusMonths(1)
        HALF_YEAR -> now.minusMonths(6)
        YEAR -> now.minusYears(1)
    }?.toInstant()?.toEpochMilli()
}

data class MessageSearchFilter(
    val unreadOnly: Boolean = false,
    val simSubIds: Set<Int> = emptySet(),
    val transports: Set<MessageTransport> = emptySet(),
    val phoneNumber: String? = null,
    val phoneDisplayName: String? = null,
    val date: SearchDate = SearchDate.ANY,
) {
    val effectiveTransports: Set<MessageTransport> get() = transports.takeIf { it.size == 1 }.orEmpty()
    val hasEffectivePhoneNumber: Boolean get() = SearchPhoneNumbers.digits(phoneNumber).isNotEmpty()
    fun isActive(): Boolean = unreadOnly || simSubIds.isNotEmpty() || effectiveTransports.isNotEmpty() ||
        hasEffectivePhoneNumber || date != SearchDate.ANY

    fun normalizeSims(activeIds: Set<Int>): MessageSearchFilter {
        val remaining = simSubIds.intersect(activeIds)
        return copy(simSubIds = remaining.takeUnless { it == activeIds }.orEmpty())
    }
}

data class MessageSearchRequest(
    val query: String,
    val filter: MessageSearchFilter,
    val beforeTimestampExclusive: Long?,
) {
    val hasEffectiveQuery: Boolean get() = query.isNotBlank()
    fun isActive(): Boolean = hasEffectiveQuery || filter.isActive()
}

data class MessageSearchBatch(val messages: List<MessageModel>, val incomplete: Boolean)
