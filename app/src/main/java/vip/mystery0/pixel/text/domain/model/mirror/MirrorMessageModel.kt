package vip.mystery0.pixel.text.domain.model.mirror

/** 原始快照中的整数使用十进制字符串，避免经过浮点数损失精度。 */
data class RawProviderValue(val column: String, val type: Int, val value: String?)

data class MirrorAddressModel(
    val sourceId: Long?, val type: Int?, val charset: Int?, val address: String?,
    val normalizedAddress: String?, val rawValues: List<RawProviderValue>,
)

enum class MirrorAttachmentState {
    UNKNOWN, PENDING_DOWNLOAD, SOURCE_PRESENT, COPYING, READY, COPY_FAILED, SOURCE_UNREADABLE,
}

data class MirrorAttachmentModel(
    val state: MirrorAttachmentState, val localUri: String?, val sourceUri: String?,
    val byteCount: Long?, val sha256: String?, val error: String?,
)

data class MirrorPartModel(
    val sourceId: Long, val sequence: Int?, val mimeType: String?, val charset: Int?,
    val name: String?, val filename: String?, val contentId: String?, val contentLocation: String?,
    val text: String?, val rawValues: List<RawProviderValue>, val attachment: MirrorAttachmentModel?,
)

data class MirrorMessageModel(
    val localId: Long, val key: SourceMessageKey, val revision: Long, val threadId: Long?,
    val timestamp: Long?, val originalDate: Long?, val dateUnit: String,
    val subscriptionId: Int?, val boxType: Int?, val read: Int?, val seen: Int?,
    val body: String?, val address: String?, val subject: String?, val decodedSubject: String?,
    val pduType: Int?, val downloadStatus: Int?, val structureComplete: Boolean,
    val rawValues: List<RawProviderValue>, val addresses: List<MirrorAddressModel>,
    val parts: List<MirrorPartModel>,
)
