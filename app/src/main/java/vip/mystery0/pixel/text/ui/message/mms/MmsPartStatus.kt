package vip.mystery0.pixel.text.ui.message.mms

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.util.AppStrings

data class MmsPartStatusInfo(
    val text: String,
    val actionable: Boolean,
    val busy: Boolean = false,
)

fun MmsPartContent.statusInfo(): MmsPartStatusInfo {
    if (issue == "preparing") return MmsPartStatusInfo(AppStrings.get(R.string.msg_mms_part_preparing), actionable = false, busy = true)
    return when (state) {
        MirrorAttachmentState.READY -> MmsPartStatusInfo(
            text = readyStatusText(),
            actionable = true,
        )
        MirrorAttachmentState.PENDING_DOWNLOAD ->
            MmsPartStatusInfo(AppStrings.get(R.string.msg_mms_part_pending_download), actionable = false, busy = true)
        MirrorAttachmentState.SOURCE_PRESENT ->
            MmsPartStatusInfo(AppStrings.get(R.string.msg_mms_part_source_present), actionable = false, busy = true)
        MirrorAttachmentState.COPYING ->
            MmsPartStatusInfo(AppStrings.get(R.string.msg_mms_part_copying), actionable = false, busy = true)
        MirrorAttachmentState.COPY_FAILED ->
            MmsPartStatusInfo(AppStrings.get(R.string.msg_mms_part_copy_failed), actionable = false)
        MirrorAttachmentState.SOURCE_UNREADABLE ->
            MmsPartStatusInfo(AppStrings.get(R.string.msg_mms_part_source_unreadable), actionable = false)
        MirrorAttachmentState.UNKNOWN ->
            MmsPartStatusInfo(AppStrings.get(R.string.msg_mms_part_unknown), actionable = false)
    }
}

private fun MmsPartContent.readyStatusText(): String {
    val inlineCopy = inlineTextCopy
    val availableText = AppStrings.get(
        if (inlineCopy) R.string.msg_mms_part_available_utf8 else R.string.msg_mms_part_available_original
    )
    return when (issue) {
        null -> AppStrings.get(if (inlineCopy) R.string.msg_mms_part_ready_utf8 else R.string.msg_mms_part_ready_original)
        "too_large", "html_too_large" -> AppStrings.get(R.string.msg_mms_part_issue_too_large, availableText)
        "unsupported_charset", "invalid_encoding" -> AppStrings.get(R.string.msg_mms_part_issue_charset, availableText)
        "read_failed", "source_unreadable", "unavailable" -> AppStrings.get(R.string.msg_mms_part_issue_unreadable, availableText)
        "multipart_invalid" -> AppStrings.get(R.string.msg_mms_part_issue_multipart_invalid, availableText)
        "multipart_ambiguous" -> AppStrings.get(R.string.msg_mms_part_issue_multipart_ambiguous, availableText)
        "multipart_unresolved" -> AppStrings.get(R.string.msg_mms_part_issue_multipart_unresolved, availableText)
        "multipart_no_usable_alternative" -> AppStrings.get(R.string.msg_mms_part_issue_multipart_no_alt, availableText)
        "smil_too_large" -> AppStrings.get(R.string.msg_mms_part_issue_smil_too_large, availableText)
        "smil_unsafe_xml" -> AppStrings.get(R.string.msg_mms_part_issue_smil_unsafe, availableText)
        "smil_invalid_xml", "smil_invalid_structure", "smil_depth_limit", "smil_page_limit" ->
            AppStrings.get(R.string.msg_mms_part_issue_smil_invalid, availableText)
        "smil_missing_reference", "smil_ambiguous_reference", "smil_ambiguous_document" ->
            AppStrings.get(R.string.msg_mms_part_issue_smil_missing_ref, availableText)
        "smil_invalid_duration" -> AppStrings.get(R.string.msg_mms_part_issue_smil_duration, availableText)
        else -> availableText
    }
}

@Composable
fun MmsPartStatus(
    part: MmsPartContent,
    modifier: Modifier = Modifier,
) {
    val status = part.statusInfo()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = status.text,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.8f),
        )
    }
}
