package vip.mystery0.pixel.text.domain.model.mms

/** 会话中的图文布局；原始演示结构及附件入口仍由完整内容模型保留。 */
data class MmsDisplayContentModel(
    val attachmentPages: List<MmsPresentationPage>,
    val bodyParts: List<MmsPartContent>,
)

/** 按演示顺序提取正文，重复引用只展示一次；未引用的已选内容接在后面。 */
fun MmsContentModel.toDisplayContent(): MmsDisplayContentModel {
    val partsById = parts.associateBy { it.key.partId }
    val referencedIds = pages.flatMap { it.partIds }.toSet()
    val orderedIds = (pages.flatMap { it.partIds } + bodyPartIds).distinct()
    val bodyParts = orderedIds.mapNotNull(partsById::get).filter {
        it.kind == MmsContentKind.TEXT || it.kind == MmsContentKind.HTML
    }
    val bodyIds = bodyParts.map { it.key.partId }.toSet()
    val attachmentPages = pages.mapNotNull { page ->
        val ids = page.partIds.filter { it !in bodyIds && it in partsById }
        if (ids.isEmpty()) null else page.copy(partIds = ids)
    } + bodyPartIds.filter { it !in referencedIds && it !in bodyIds && it in partsById }.map {
        MmsPresentationPage(listOf(it), durationMillis = 5000L)
    }
    return MmsDisplayContentModel(attachmentPages, bodyParts)
}
