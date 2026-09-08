package vip.mystery0.pixel.text.domain.model.mms

import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mirror.MirrorPartModel
import vip.mystery0.pixel.text.domain.parser.mms.MmsMimeTypes

/**
 * 读取、展示与导出共用的来源选择。非空本地原件始终优先，不用 Provider text 重编码覆盖。
 * 历史 HTML 可能只有非空 text 和 READY 空占位流，此时只能提供明确标注的 UTF-8 副本。
 */
fun MirrorPartModel.usesInlineTextCopy(): Boolean {
    if (text == null || !MmsMimeTypes.canExportInlineText(mimeType)) return false
    val originalReady = attachment?.state == MirrorAttachmentState.READY && attachment.localUri != null
    return !originalReady || (attachment?.byteCount == 0L && text.isNotEmpty())
}
