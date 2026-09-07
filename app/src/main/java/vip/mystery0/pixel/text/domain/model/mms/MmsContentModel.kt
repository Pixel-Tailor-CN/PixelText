package vip.mystery0.pixel.text.domain.model.mms

import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey

/** partId 是系统彩信 part 标识，必须与消息键一起使用。 */
data class MmsPartKey(val message: SourceMessageKey, val partId: Long)

enum class MmsContentKind { TEXT, IMAGE, AUDIO, VIDEO, HTML, CONTACT, CALENDAR, FILE, SMIL, MULTIPART }

/**
 * 完整内容视图，不覆盖镜像中的原始 MIME、名称或字节。
 * revision 是消息版本；附件状态改变时版本可能不变，订阅方应接收完整的新模型。
 * mimeType 已规范化；displayName 只用于显示，不能作为文件路径。
 * byteCount 为原件长度（内联文本没有原件时为 UTF-8 长度），未知时为空。
 * state 保留镜像附件状态；内联文本可读时为 READY，不代表解码一定成功。
 * localUri 为本地原件，解析失败/超限仍保留；text 仅包含完整、成功解码的文本。
 * contentId、contentLocation 供消息内资源寻址使用，不代表允许访问外部地址。
 * issue 为内部英文分类，中文用户文案由展示层统一映射，不能直接展示异常信息：
 * preparing=准备中，pending_download=等待下载，copying=复制中，source_present=等待复制，
 * copy_failed=复制失败，source_unreadable=源不可读，unavailable=尚无可读内容，
 * too_large=超过读取预算，unsupported_charset=不支持声明字符集，
 * invalid_encoding=编码损坏或 BOM 与声明冲突，read_failed=本地读取失败。
 * 未知 MIME 归为 FILE，不视为解析失败；issue 为空表示本层没有发现问题。
 */
data class MmsPartContent(
    val key: MmsPartKey,
    val revision: Long,
    val kind: MmsContentKind,
    val mimeType: String,
    val displayName: String,
    val byteCount: Long?,
    val state: MirrorAttachmentState,
    val localUri: String?,
    val text: String?,
    val contentId: String?,
    val contentLocation: String?,
    val issue: String?,
    /** 仅在原容器与本消息子项唯一匹配时设置；空列表不表示恢复成功。 */
    val childPartIds: List<Long> = emptyList(),
    val multipartResolved: Boolean = false,
    /** 镜像原件的 SHA-256；附件更新可能不改变消息 revision。 */
    val contentHash: String? = null,
)

/** SMIL 派生的顺序页；尚未解析展示结构时为空列表。 */
data class MmsPresentationPage(val partIds: List<Long>, val durationMillis: Long)

/**
 * subject 优先使用镜像解码标题；parts 保留所有部件，包含未知类型。
 * summary 是短摘要；searchableText 为本层成功读取的普通文本，不包含 HTML/SMIL 源码。
 * pendingDownload 表示消息或部件仍待下载；准备中的模型使用 summary=正在准备彩信内容。
 */
data class MmsContentModel(
    val key: SourceMessageKey,
    val revision: Long,
    val subject: String?,
    val parts: List<MmsPartContent>,
    val pages: List<MmsPresentationPage>,
    val summary: String,
    val searchableText: String,
    val pendingDownload: Boolean,
    /** 正文展示选择；parts 始终保留全部原件。SMIL 已引用项可在页中展示。 */
    val bodyPartIds: List<Long> = emptyList(),
    /** 未被 SMIL 引用的叶子项（含 alternative 未选项），均保留附件入口。 */
    val attachmentPartIds: List<Long> = emptyList(),
    /** 稳定英文问题分类，不包含源文或异常信息。 */
    val issues: List<String> = emptyList(),
)
