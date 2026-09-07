package vip.mystery0.pixel.text.domain.model.mms

import java.time.Instant

/** 全天日期以 UTC 零时编码，结束日期不包含在事件内，符合系统日历约定。 */
data class MmsCalendarModel(
    val title: String?,
    val start: Instant?,
    val end: Instant?,
    val zoneId: String?,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
    val recurrence: String?,
    val importWarning: String?,
    /** 含无法可靠确定的时间时只允许打开原件。 */
    val canImport: Boolean = start != null && end != null,
)
