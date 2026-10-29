package vip.mystery0.pixel.text.data.source

import com.squareup.moshi.JsonClass
import vip.mystery0.pixel.text.domain.hub.*
import java.time.Instant

@JsonClass(generateAdapter = true)
internal data class SampleReportBody(
    val androidId: String,
    val installationId: String,
    val clientRequestId: String,
    val content: String,
    val sender: String,
    val category: String,
    val appVersion: String,
    val ruleVersion: String,
    val modelVersion: String,
) {
    fun toDomain() = SampleReportRequest(androidId, installationId, clientRequestId, content,
        sender, category, appVersion, ruleVersion, modelVersion)
}

internal fun SampleReportRequest.toBody() = SampleReportBody(androidId, installationId,
    clientRequestId, content, sender, category, appVersion, ruleVersion, modelVersion)

@JsonClass(generateAdapter = true)
internal data class SampleReportQuery(
    val androidId: String,
    val installationId: String,
    val cursor: String? = null,
    val limit: Int = 20,
)

@JsonClass(generateAdapter = true)
internal data class SampleReportDetailQuery(
    val androidId: String,
    val installationId: String,
    val reportId: String,
)

@JsonClass(generateAdapter = true)
internal data class SampleReportDto(
    val id: String,
    val source: String = "",
    val contentPreview: String = "",
    val submittedCategory: String = "",
    val reviewedCategory: String = "",
    val status: String = "unknown",
    val reasonCode: String = "",
    val reasonText: String = "",
    val reviewMethod: String = "",
    val submittedAt: String = "",
    val statusUpdatedAt: String = "",
    val reviewedAt: String? = null,
    val submittedContent: String = "",
    val submittedSender: String = "",
) {
    fun summary(): SampleReportSummary {
        val state = SampleReportStatus.fromValue(status)
        return SampleReportSummary(id, source,
            if (state == SampleReportStatus.REMOVED) "" else contentPreview,
            submittedCategory, reviewedCategory, state, reasonCode, reasonText, reviewMethod,
            submittedAt.instantOrNull(), statusUpdatedAt.instantOrNull(), reviewedAt?.instantOrNull())
    }

    fun detail(): SampleReportDetail {
        val summary = summary()
        val removed = summary.status == SampleReportStatus.REMOVED
        return SampleReportDetail(summary, if (removed) "" else submittedContent,
            if (removed) "" else submittedSender)
    }
}

private fun String.instantOrNull(): Instant? = try { Instant.parse(this) } catch (_: Exception) { null }

@JsonClass(generateAdapter = true)
internal data class SampleReportPageDto(val items: List<SampleReportDto>, val nextCursor: String? = null)

@JsonClass(generateAdapter = true)
internal data class SampleReportDetailDto(val report: SampleReportDto)

@JsonClass(generateAdapter = true)
internal data class SampleReportSubmissionDto(val report: SampleReportDto, val created: Boolean)

@JsonClass(generateAdapter = true)
internal data class SampleReportErrorDto(val code: String = "")

@JsonClass(generateAdapter = true)
internal data class SampleReportDraftDto(val content: String, val sender: String, val category: String)
