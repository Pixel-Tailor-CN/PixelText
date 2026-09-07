package vip.mystery0.pixel.text.domain.parser.mms

import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent

/** 只在有可靠父子关系的 alternative 内选择；原件列表由调用方完整保留。 */
class MmsMultipartResolver {
    data class Result(val parts: List<MmsPartContent>, val issues: List<String>)

    fun select(parts: List<MmsPartContent>): List<MmsPartContent> = selectWithIssues(parts).parts

    fun selectWithIssues(parts: List<MmsPartContent>): Result {
        val issues = linkedSetOf<String>()
        val byId = parts.associateBy { it.key.partId }
        val leaves = parts.filter { it.kind !in setOf(MmsContentKind.MULTIPART, MmsContentKind.SMIL) }
        val edges = parts.flatMap { it.childPartIds }
        if (byId.size != parts.size || parts.map { it.key.message }.distinct().size > 1 ||
            edges.distinct().size != edges.size || edges.any { it !in byId }) {
            return Result(leaves, listOf("multipart_ambiguous"))
        }
        fun usable(part: MmsPartContent): Boolean = part.issue == null &&
            part.state == MirrorAttachmentState.READY && when (part.kind) {
                MmsContentKind.HTML, MmsContentKind.TEXT -> !part.text.isNullOrBlank()
                MmsContentKind.IMAGE, MmsContentKind.AUDIO, MmsContentKind.VIDEO -> part.localUri != null
                else -> false
            }
        val visited = mutableSetOf<Long>()
        fun choose(part: MmsPartContent, ancestors: Set<Long>): List<MmsPartContent> {
            if (part.key.partId in ancestors || ancestors.size > 8) {
                issues += "multipart_ambiguous"
                return emptyList()
            }
            visited += part.key.partId
            if (part.kind == MmsContentKind.SMIL) return emptyList()
            if (part.kind != MmsContentKind.MULTIPART) return listOf(part)
            if (!part.multipartResolved) {
                issues += "multipart_unresolved"
                return emptyList()
            }
            val branches = part.childPartIds.map { choose(byId.getValue(it), ancestors + part.key.partId) }
            if (!part.mimeType.endsWith("alternative")) return branches.flatten()
            fun complete(branch: List<MmsPartContent>) = branch.all {
                it.issue == null && it.state == MirrorAttachmentState.READY &&
                    (it.text != null || it.localUri != null)
            }
            fun rank(branch: List<MmsPartContent>): Int = when {
                complete(branch) && branch.any { it.kind == MmsContentKind.HTML && usable(it) } -> 0
                branch.any { it.kind == MmsContentKind.TEXT && usable(it) } -> 1
                branch.any { it.kind in setOf(MmsContentKind.IMAGE, MmsContentKind.AUDIO, MmsContentKind.VIDEO) && usable(it) } -> 2
                else -> 3
            }
            val best = branches.minByOrNull(::rank)
            if (best == null || rank(best) == 3) {
                issues += "multipart_no_usable_alternative"
                return branches.flatten()
            }
            return best
        }
        val roots = parts.filter { it.key.partId !in edges }
        val selected = roots.flatMap { choose(it, emptySet()) }
        // 环或不完整图不允许隐藏任何实际叶子项。
        if (visited.size != parts.size || "multipart_ambiguous" in issues || "multipart_unresolved" in issues) {
            if (visited.size != parts.size) issues += "multipart_ambiguous"
            return Result(leaves, issues.toList())
        }
        val selectedIds = selected.map { it.key.partId }.toSet()
        return Result(leaves.filter { it.key.partId in selectedIds }, issues.toList())
    }
}
