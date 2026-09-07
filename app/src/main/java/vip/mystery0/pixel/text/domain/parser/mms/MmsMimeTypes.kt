package vip.mystery0.pixel.text.domain.parser.mms

import java.util.Locale
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind

object MmsMimeTypes {
    fun normalize(raw: String?): String = raw?.lowercase(Locale.ROOT)
        ?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"

    fun classify(raw: String?): MmsContentKind {
        val mime = normalize(raw)
        return when {
            mime in setOf("text/html", "application/xhtml+xml", "application/vnd.wap.xhtml+xml") -> MmsContentKind.HTML
            mime in setOf("text/vcard", "text/x-vcard", "text/directory", "application/vcard", "application/x-vcard") -> MmsContentKind.CONTACT
            mime in setOf("text/calendar", "text/x-vcalendar", "text/vcalendar", "application/ics", "application/icalendar") -> MmsContentKind.CALENDAR
            mime in setOf("application/smil", "application/smil+xml", "text/smil") -> MmsContentKind.SMIL
            mime.startsWith("multipart/") || mime.startsWith("application/vnd.wap.multipart.") -> MmsContentKind.MULTIPART
            mime.startsWith("image/") -> MmsContentKind.IMAGE
            mime.startsWith("audio/") || mime in setOf("application/ogg", "application/x-ogg") -> MmsContentKind.AUDIO
            mime.startsWith("video/") -> MmsContentKind.VIDEO
            // 网页样式/脚本只保留附件，不能作为普通正文参与摘要。
            mime in setOf("text/css", "text/javascript", "text/ecmascript", "text/vbscript", "text/x-javascript") -> MmsContentKind.FILE
            mime.startsWith("text/") -> MmsContentKind.TEXT
            else -> MmsContentKind.FILE
        }
    }

    fun isText(kind: MmsContentKind): Boolean = kind in setOf(
        MmsContentKind.TEXT, MmsContentKind.HTML, MmsContentKind.CONTACT,
        MmsContentKind.CALENDAR, MmsContentKind.SMIL,
    )

    /** 可导出 Provider 内联字符串不等于可显示为正文，样式/脚本也保留 UTF-8 副本。 */
    fun canExportInlineText(raw: String?): Boolean = normalize(raw).startsWith("text/") || isText(classify(raw))

    /** MIME 参数仅用于声明字符集，不修改原始镜像。 */
    fun charsetName(raw: String?): String? = raw?.split(';')?.drop(1)?.firstNotNullOfOrNull {
        val pair = it.trim().split('=', limit = 2)
        if (pair.size == 2 && pair[0].trim().equals("charset", ignoreCase = true)) {
            pair[1].trim().removeSurrounding("\"").removeSurrounding("'")
        } else null
    }
}
