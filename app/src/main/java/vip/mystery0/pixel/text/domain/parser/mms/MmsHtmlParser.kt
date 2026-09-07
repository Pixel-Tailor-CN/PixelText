package vip.mystery0.pixel.text.domain.parser.mms

import java.net.URI
import java.util.UUID
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.parser.Parser
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPartKey

/** 派生页面仅保存消息内身份；原始 HTML、附件名称与字节不变。 */
data class MmsHtmlDocument(
    val html: String,
    val plainText: String,
    val resourceMap: Map<String, MmsPartKey>,
    val origin: String,
    val documentUrl: String,
    /** 应用生成的导航标识 → 已校验的原目标，不依赖浏览器对来信 URL 的规范化。 */
    val externalLinks: Map<String, String>,
    /** 只记录分类与数量，不暴露未解析的原始引用。 */
    val unresolvedReferenceCount: Int,
    val issue: String? = null,
)

class MmsHtmlParser {
    fun prepare(part: MmsPartContent, allParts: List<MmsPartContent>): MmsHtmlDocument {
        val token = UUID.randomUUID().toString().replace("-", "")
        val origin = "https://mms-$token.invalid"
        val path = "/message/${part.key.message.sourceId}/${part.key.partId}/"
        val documentUrl = "$origin${path}index.html"
        // 在构建 DOM 前限制输入；jsoup 在建树时限制栈深度，非事后裁剪。
        val input = part.text.orEmpty()
        if (input.length > MAX_HTML_CHARACTERS) return MmsHtmlDocument(
            "", "", emptyMap(), origin, documentUrl, emptyMap(), 0, "html_too_large",
        )
        val source = Jsoup.parse(input, "", Parser.htmlParser().setMaxDepth(64))
        // 来信样式全部丢弃，避免 CSS URL、导入、覆盖控件和隐藏内容。
        source.select("script,style,form,iframe,frame,frameset,object,embed,applet,svg,math,template,noscript,base,meta,link").remove()
        val safe = Safelist.none().addTags(
            "a", "abbr", "b", "blockquote", "br", "caption", "code", "col", "colgroup", "dd", "del", "div", "dl", "dt",
            "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "s", "small",
            "span", "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tfoot", "tr", "u", "ul",
        ).addAttributes("a", "href").addAttributes("img", "src", "alt")
            .addAttributes("td", "colspan", "rowspan").addAttributes("th", "colspan", "rowspan")
        val doc = Cleaner(safe).clean(source)
        val resources = linkedMapOf<String, MmsPartKey>()
        val links = linkedMapOf<String, String>()
        var unresolved = 0
        val messageParts = allParts.filter { it.key.message == part.key.message && it.mimeType in IMAGE_TYPES }
        val baseLocation = relativeLocation(part.contentLocation.orEmpty())
        doc.select("img").forEach { image ->
            val reference = image.attr("src").trim()
            val candidates = if (reference.startsWith("cid:", ignoreCase = true)) {
                val cid = reference.substring(4).removeSurrounding("<", ">")
                messageParts.filter { it.contentId?.trim()?.removeSurrounding("<", ">") == cid }
            } else {
                val relative = relativeLocation(reference)
                val resolved = relative?.let { baseLocation?.resolve(it)?.normalize() ?: it }
                messageParts.filter { relative != null && relativeLocation(it.contentLocation.orEmpty()) == resolved }
            }
            val target = candidates.singleOrNull()
            if (target == null) {
                unresolved++
                image.removeAttr("src")
                if (image.attr("alt").isBlank()) image.attr("alt", "图片不可用")
            } else {
                val url = "$origin${path}resource/${target.key.partId}"
                resources[url] = target.key
                image.attr("src", url)
            }
        }
        doc.select("a").forEach { anchor ->
            val href = anchor.attr("href").trim()
            val uri = runCatching { URI(href) }.getOrNull()
            val web = uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank() && uri.rawUserInfo == null
            val system = uri?.scheme?.lowercase() in setOf("tel", "mailto") && !uri?.rawSchemeSpecificPart.isNullOrBlank()
            if (uri != null && (web || system)) {
                // 固定小写来源与非空路径由应用构造，不将外部目标交给 WebView 规范化。
                val navigationUrl = "$origin${path}link/${links.size}"
                anchor.attr("href", navigationUrl)
                links[navigationUrl] = uri.toASCIIString()
            } else anchor.removeAttr("href")
        }
        doc.select("td,th").forEach { cell ->
            listOf("colspan", "rowspan").forEach { name ->
                val number = cell.attr(name).toIntOrNull()
                if (number == null || number !in 1..100) cell.removeAttr(name)
            }
        }
        // 宽表格有独立横向滚动区域，不缩小整页字体。
        doc.select("table").forEach { it.wrap("<div class=\"table-scroll\"></div>") }
        val textBody = doc.body().clone()
        textBody.select("td,th").forEach { it.appendText("\t") }
        textBody.select("p,div,h1,h2,h3,h4,h5,h6,li,tr,blockquote,pre").forEach { it.appendText("\n") }
        val plainText = textBody.wholeText().trim()
        val policy = "default-src 'none'; img-src $origin; style-src 'nonce-$token'; " +
            "script-src 'none'; connect-src 'none'; frame-src 'none'; child-src 'none'; " +
            "object-src 'none'; font-src 'none'; media-src 'none'; form-action 'none'; base-uri 'none'"
        doc.head().appendElement("meta").attr("charset", "utf-8")
        doc.head().appendElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", policy)
        doc.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
        doc.head().appendElement("meta").attr("name", "referrer").attr("content", "no-referrer")
        doc.head().appendElement("style").attr("nonce", token).appendChild(DataNode(STYLE))
        return MmsHtmlDocument(doc.outerHtml(), plainText, resources.toMap(), origin, documentUrl, links.toMap(), unresolved)
    }

    /** 拒绝协议、绝对路径、反斜线及越过消息目录的相对路径。 */
    private fun relativeLocation(value: String): URI? = runCatching {
        if (value.isBlank() || '\\' in value) return null
        val uri = URI(value.trim()).normalize()
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.path.startsWith("/") || uri.path.startsWith("../") || uri.path == "..") null else uri
    }.getOrNull()

    companion object {
        const val MAX_HTML_CHARACTERS = 256 * 1024
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/avif")
        private const val STYLE = """
            :root { color-scheme: light dark; } body { margin:16px; font:16px/1.55 sans-serif; overflow-wrap:anywhere; }
            img { max-width:100%; height:auto; } .table-scroll { overflow-x:auto; max-width:100%; }
            table { border-collapse:collapse; } td,th { padding:8px; border:1px solid #888; min-width:80px; }
            pre { white-space:pre-wrap; } a { color:#245fc7; } body { background:#fff; color:#202124; }
            @media(prefers-color-scheme:dark) { body { background:#171717; color:#eee; } a { color:#aac7ff; } }
        """
    }
}
