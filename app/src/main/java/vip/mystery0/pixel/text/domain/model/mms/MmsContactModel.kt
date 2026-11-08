package vip.mystery0.pixel.text.domain.model.mms

/** 标签沿用名片声明；原始文件始终由所在 part 保留。 */
data class MmsContactValue(val value: String, val label: String = "")

data class MmsContactModel(
    val name: String?,
    val organization: String?,
    val phones: List<MmsContactValue>,
    val emails: List<MmsContactValue>,
    val addresses: List<MmsContactValue>,
    val title: String?,
    val notes: String?,
    val photoPartId: Long? = null,
    /** 只保存有界图片字节，不保存远程 URL。 */
    val photoBytes: ByteArray? = null,
    /** 仅用于仓库在本消息中唯一匹配 CID/Content-Location。 */
    val photoReference: String? = null,
    val importWarning: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || other is MmsContactModel &&
        name == other.name && organization == other.organization && phones == other.phones &&
        emails == other.emails && addresses == other.addresses && title == other.title &&
        notes == other.notes && photoPartId == other.photoPartId &&
        photoBytes.contentEquals(other.photoBytes) && photoReference == other.photoReference &&
        importWarning == other.importWarning

    override fun hashCode(): Int = listOf(
        name, organization, phones, emails, addresses, title, notes, photoPartId,
        photoBytes?.contentHashCode(), photoReference, importWarning,
    ).hashCode()
}
