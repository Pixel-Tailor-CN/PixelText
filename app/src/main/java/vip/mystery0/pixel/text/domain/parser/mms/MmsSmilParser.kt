package vip.mystery0.pixel.text.domain.parser.mms

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPresentationPage

/** 仅解析本消息演示结构；不加载外部实体、样式、脚本或网络资源。 */
class MmsSmilParser {
    data class Result(val pages: List<MmsPresentationPage>, val issues: List<String>)

    fun parse(text: String, parts: List<MmsPartContent>): List<MmsPresentationPage> =
        parseWithIssues(text, parts).pages

    fun parseWithIssues(text: String, parts: List<MmsPartContent>): Result {
        if (parts.map { it.key.message }.distinct().size > 1) return failure("smil_ambiguous_reference")
        if (text.length > MAX_BYTES || utf8Size(text) > MAX_BYTES) return failure("smil_too_large")
        if (text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true)) return failure("smil_unsafe_xml")
        val pages = mutableListOf<MmsPresentationPage>()
        val issues = linkedSetOf<String>()
        val stack = mutableListOf<String>()
        var pageIds: MutableList<Long>? = null
        var pageDuration = DEFAULT_DURATION
        var foundRoot = false
        var foundBody = false
        var inBody = false
        fun addPage(ids: List<Long>, duration: Long) {
            require(pages.size < MAX_PAGES) { "smil_page_limit" }
            pages += MmsPresentationPage(ids.distinct(), duration)
        }
        return try {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(StringReader(text))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.DOCDECL -> return failure("smil_unsafe_xml")
                    XmlPullParser.START_TAG -> {
                        if (parser.depth > MAX_DEPTH) return failure("smil_depth_limit")
                        val name = parser.name
                        if (stack.isEmpty()) {
                            if (foundRoot || name != "smil") return failure("smil_invalid_xml")
                            foundRoot = true
                        }
                        val parent = stack.lastOrNull()
                        if (name == "body") {
                            if (parent != "smil" || foundBody) return failure("smil_invalid_structure")
                            foundBody = true
                            inBody = true
                        }
                        if (inBody && name != "body" &&
                            (name !in MEDIA_TAGS + setOf("seq", "par") || parent !in setOf("body", "seq", "par"))) {
                            return failure("smil_invalid_structure")
                        }
                        // 不猜测并行容器中的顺序子演示，避免结束标签产生错误页序。
                        if (inBody && name == "seq" && parent == "par") return failure("smil_invalid_structure")
                        if (inBody && name == "par") {
                            if (pageIds != null || parent !in setOf("body", "seq")) return failure("smil_invalid_structure")
                            pageIds = mutableListOf()
                            pageDuration = duration(parser.getAttributeValue(null, "dur"), issues)
                        }
                        if (inBody && name in MEDIA_TAGS && parent in setOf("par", "seq", "body")) {
                            val src = parser.getAttributeValue(null, "src")
                            val candidates = resolve(src, parts)
                            val part = candidates.singleOrNull()
                            if (part == null) issues += if (candidates.isEmpty()) "smil_missing_reference" else "smil_ambiguous_reference"
                            val ids = part?.let { listOf(it.key.partId) }.orEmpty()
                            if (pageIds != null) pageIds.addAll(ids)
                            else addPage(ids, duration(parser.getAttributeValue(null, "dur"), issues))
                        }
                        stack += name
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "par" && inBody) {
                            addPage(pageIds.orEmpty(), pageDuration)
                            pageIds = null
                        }
                        if (parser.name == "body") inBody = false
                        if (stack.isEmpty() || stack.removeAt(stack.lastIndex) != parser.name) return failure("smil_invalid_xml")
                    }
                }
                event = parser.nextToken()
            }
            if (!foundRoot || stack.isNotEmpty()) failure("smil_invalid_xml")
            else if (!foundBody) failure("smil_invalid_structure") else Result(pages, issues.toList())
        } catch (error: Exception) {
            // 不返回半份演示结构；所有实际 part 仍由仓库保留在附件区。
            failure(if (error.message == "smil_page_limit") "smil_page_limit" else "smil_invalid_xml")
        }
    }

    private fun resolve(src: String?, parts: List<MmsPartContent>): List<MmsPartContent> {
        val reference = src?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val cid = reference.startsWith("cid:", true)
        val value = if (cid) reference.substring(4).removeSurrounding("<", ">") else reference
        return parts.filter {
            it.kind !in setOf(MmsContentKind.SMIL, MmsContentKind.MULTIPART) &&
                if (cid) it.contentId?.trim()?.removeSurrounding("<", ">") == value
                else it.contentLocation == value
        }
    }

    private fun duration(raw: String?, issues: MutableSet<String>): Long {
        if (raw == null) return DEFAULT_DURATION
        val value = raw.trim()
        val seconds = if (value.length > 64) null else when {
            value.endsWith("ms") -> value.dropLast(2).toDoubleOrNull()?.div(1000)
            value.endsWith("s") -> value.dropLast(1).toDoubleOrNull()
            ':' in value -> {
                val fields = value.split(':')
                val numbers = fields.map { it.toDoubleOrNull() }
                if (fields.size !in 2..3 || numbers.any { it == null || !it.isFinite() || it < 0 } ||
                    numbers.last()!! >= 60 || numbers.dropLast(1).any { it!! % 1.0 != 0.0 } ||
                    (fields.size == 3 && numbers[1]!! >= 60)) null
                else numbers.fold(0.0) { total, number -> total * 60 + number!! }
            }
            else -> value.toDoubleOrNull()
        }
        if (seconds == null || !seconds.isFinite() || seconds < 0) {
            issues += "smil_invalid_duration"
            return DEFAULT_DURATION
        }
        return (seconds.coerceIn(0.1, 300.0) * 1000).toLong()
    }

    private fun utf8Size(text: String): Long = text.codePoints().mapToLong {
        when { it < 0x80 -> 1L; it < 0x800 -> 2L; it < 0x10000 -> 3L; else -> 4L }
    }.sum()

    private fun failure(issue: String) = Result(emptyList(), listOf(issue))

    private companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_DEPTH = 32
        const val MAX_PAGES = 128
        const val DEFAULT_DURATION = 5000L
        val MEDIA_TAGS = setOf("text", "img", "audio", "video", "ref", "animation")
    }
}
