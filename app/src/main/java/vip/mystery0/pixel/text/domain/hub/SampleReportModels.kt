package vip.mystery0.pixel.text.domain.hub

import java.time.Instant

data class SampleReportIdentity(val androidId: String, val installationId: String)

/** 已经人工确认的完整负载；恢复时不能重新读取版本或设备字段。 */
data class SampleReportRequest(
    val androidId: String,
    val installationId: String,
    val clientRequestId: String,
    val content: String,
    val sender: String,
    val category: String,
    val appVersion: String,
    val ruleVersion: String,
    val modelVersion: String,
)

enum class SampleReportStatus(val label: String) {
    PENDING("等待审核"), REVIEWING("自动审核中"), MANUAL_REVIEW("人工复核"),
    APPROVED("已通过"), REJECTED("未通过"), RESTRICTED("暂不可采纳"),
    REMOVED("已移除"), UNKNOWN("状态待确认");

    companion object {
        fun fromValue(value: String): SampleReportStatus = when (value) {
            "pending" -> PENDING
            "reviewing" -> REVIEWING
            "manualReview" -> MANUAL_REVIEW
            "approved" -> APPROVED
            "rejected" -> REJECTED
            "restricted" -> RESTRICTED
            "removed" -> REMOVED
            else -> UNKNOWN
        }
    }
}

data class SampleReportSummary(
    val id: String,
    val source: String,
    val contentPreview: String,
    val submittedCategory: String,
    val reviewedCategory: String,
    val status: SampleReportStatus,
    val reasonCode: String,
    val reasonText: String,
    val reviewMethod: String,
    val submittedAt: Instant?,
    val statusUpdatedAt: Instant?,
    val reviewedAt: Instant?,
)

data class SampleReportDetail(
    val summary: SampleReportSummary,
    val submittedContent: String,
    val submittedSender: String,
)

data class SampleReportPage(val items: List<SampleReportSummary>, val nextCursor: String?)
data class SampleReportSubmission(val report: SampleReportDetail, val created: Boolean)
data class SampleReportDraft(val content: String, val sender: String, val category: String)

/** 只包含面向用户的安全提示，不传播服务端原始错误体。 */
class SampleReportException(
    val code: String,
    override val message: String,
    val retryAfterSeconds: Long? = null,
    val definitelyNotAccepted: Boolean = false,
) : Exception(message)

fun isValidSampleReportId(value: String): Boolean =
    value.matches(Regex("[1-9][0-9]{0,18}")) && value.toLongOrNull() != null
