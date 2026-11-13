package vip.mystery0.pixel.text.domain.hub

import androidx.annotation.StringRes
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.util.AppStrings
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
    val learningFeedback: LearningFeedback? = null,
)

/** 随人工确认一起冻结的可选学习反馈，不包含个人参数。 */
data class LearningFeedback(
    val origin: String = "on_device_learning",
    val baseModelVersion: String,
    val personalGeneration: Long? = null,
    val originalLabel: String,
)

enum class SampleReportStatus(@param:StringRes val labelRes: Int) {
    PENDING(R.string.domain_sample_report_status_pending),
    REVIEWING(R.string.domain_sample_report_status_reviewing),
    MANUAL_REVIEW(R.string.domain_sample_report_status_manual_review),
    APPROVED(R.string.domain_sample_report_status_approved),
    REJECTED(R.string.domain_sample_report_status_rejected),
    RESTRICTED(R.string.domain_sample_report_status_restricted),
    REMOVED(R.string.domain_sample_report_status_removed),
    UNKNOWN(R.string.domain_sample_report_status_unknown);

    val label: String get() = AppStrings.get(labelRes)

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
data class SampleReportDraft(val content: String, val sender: String, val category: String,
    val learningFeedback: LearningFeedback? = null)

/** 只包含面向用户的安全提示，不传播服务端原始错误体。 */
class SampleReportException(
    val code: String,
    override val message: String,
    val retryAfterSeconds: Long? = null,
    val definitelyNotAccepted: Boolean = false,
) : Exception(message)

fun isValidSampleReportId(value: String): Boolean =
    value.matches(Regex("[1-9][0-9]{0,18}")) && value.toLongOrNull() != null
