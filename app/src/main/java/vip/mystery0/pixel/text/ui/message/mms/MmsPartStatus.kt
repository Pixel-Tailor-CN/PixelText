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
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent

data class MmsPartStatusInfo(
    val text: String,
    val actionable: Boolean,
    val busy: Boolean = false,
)

fun MmsPartContent.statusInfo(): MmsPartStatusInfo {
    if (issue == "preparing") return MmsPartStatusInfo("正在准备附件", actionable = false, busy = true)
    return when (state) {
        MirrorAttachmentState.READY -> MmsPartStatusInfo(
            text = readyStatusText(),
            actionable = true,
        )
        MirrorAttachmentState.PENDING_DOWNLOAD ->
            MmsPartStatusInfo("等待下载", actionable = false, busy = true)
        MirrorAttachmentState.SOURCE_PRESENT ->
            MmsPartStatusInfo("等待保存到本地", actionable = false, busy = true)
        MirrorAttachmentState.COPYING ->
            MmsPartStatusInfo("正在保存到本地", actionable = false, busy = true)
        MirrorAttachmentState.COPY_FAILED ->
            MmsPartStatusInfo("附件保存失败，请稍后重试", actionable = false)
        MirrorAttachmentState.SOURCE_UNREADABLE ->
            MmsPartStatusInfo("附件已删除或无法读取", actionable = false)
        MirrorAttachmentState.UNKNOWN ->
            MmsPartStatusInfo("附件尚不可用", actionable = false)
    }
}

private fun MmsPartContent.readyStatusText(): String {
    val inlineCopy = localUri == null
    val availableText = if (inlineCopy) "仍可导出 UTF-8 文本副本" else "原件仍可导出"
    return when (issue) {
        null -> if (inlineCopy) "可导出 · UTF-8 文本副本" else "可打开、保存或分享"
        "too_large" -> "内容过大，$availableText"
        "unsupported_charset", "invalid_encoding" -> "文本编码无法预览，$availableText"
        "read_failed", "source_unreadable", "unavailable" -> "内容无法预览，$availableText"
        "multipart_invalid" -> "复合附件结构异常，原件仍可导出"
        "multipart_ambiguous" -> "复合附件关系不明确，原件仍可导出"
        "multipart_unresolved" -> "复合附件结构未解析，原件仍可导出"
        "multipart_no_usable_alternative" -> "没有可预览的替代内容，原件仍可导出"
        "smil_too_large" -> "演示控制内容过大，原件仍可导出"
        "smil_unsafe_xml" -> "演示控制内容不安全，原件仍可导出"
        "smil_invalid_xml", "smil_invalid_structure", "smil_depth_limit", "smil_page_limit" ->
            "演示结构异常，原件仍可导出"
        "smil_missing_reference", "smil_ambiguous_reference", "smil_ambiguous_document" ->
            "演示附件引用不完整，原件仍可导出"
        "smil_invalid_duration" -> "演示时长无效，原件仍可导出"
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
