package vip.mystery0.pixel.text.mms

/** 接收确认与报告的只读状态，不向 UI 暴露事务、下载地址或原始 PDU。 */
data class MmsReceptionResponseState(
    val type: Int,
    val status: Int,
    val phase: String,
    val attempts: Int,
    val reason: String?,
)

data class MmsReceptionReportState(
    val eventId: String,
    val sourceMessageId: Long,
    val type: Int,
    val status: Int,
    val date: Long,
    val phase: String,
)
