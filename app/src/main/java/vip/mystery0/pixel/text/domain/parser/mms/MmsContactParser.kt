package vip.mystery0.pixel.text.domain.parser.mms

import ezvcard.io.text.VCardReader
import java.util.Locale
import kotlinx.coroutines.CancellationException
import vip.mystery0.pixel.text.domain.model.mms.MmsContactModel
import vip.mystery0.pixel.text.domain.model.mms.MmsContactValue

/** 只调用文本 API，不调用库的 URL、HTML、XML 或 JSON 入口。 */
class MmsContactParser {
    fun parse(text: String, checkCancelled: () -> Unit = {}): List<MmsContactModel> {
        if (text.length > MAX_TEXT) return listOf(failed("名片过大，请打开原件"))
        val result = mutableListOf<MmsContactModel>()
        var lines: MutableList<String>? = null
        var nested = false
        for (line in unfoldMmsContentLines(text, quotedPrintable = true, checkCancelled)) {
            checkCancelled()
            when (line.uppercase(Locale.ROOT)) {
                "BEGIN:VCARD" -> {
                    if (lines == null && result.size == MAX_CONTACTS) {
                        val last = result.last()
                        result[result.lastIndex] = last.copy(importWarning = listOfNotNull(last.importWarning,
                            "最多展示 $MAX_CONTACTS 位联系人，其余内容请打开原件").joinToString("；"))
                        break
                    }
                    if (lines != null) nested = true else lines = mutableListOf(line)
                }
                "END:VCARD" -> {
                    val block = lines ?: continue
                    block += line
                    result += if (nested) failed("嵌套名片无法完整读取，请打开原件") else parseOne(block, checkCancelled)
                    lines = null
                    nested = false
                }
                else -> lines?.add(line)
            }
        }
        if (lines != null) result += failed("名片不完整，请打开原件")
        return result.ifEmpty { listOf(failed("无法读取名片，请打开原件")) }
    }

    private fun parseOne(lines: List<String>, checkCancelled: () -> Unit): MmsContactModel = try {
        // 仅把本任务支持的属性交给库，避免 AGENT 嵌套解析及无关二进制扩展。
        val accepted = setOf("BEGIN", "END", "VERSION", "FN", "N", "ORG", "TEL", "EMAIL", "ADR", "LABEL", "TITLE", "NOTE", "PHOTO")
        val filtered = mutableListOf<String>()
        var incomplete = false
        var propertyCount = 0
        lines.forEach { line ->
            checkCancelled()
            val name = line.substringBefore(':').substringBefore(';').substringAfterLast('.').uppercase(Locale.ROOT)
            val keep = name in accepted && ++propertyCount <= 512
            if (keep) filtered += line else if (line.isNotBlank()) incomplete = true
        }
        VCardReader(filtered.joinToString("\r\n")).use { reader ->
            reader.defaultQuotedPrintableCharset = Charsets.UTF_8
            val card = reader.readNext() ?: return failed("无法读取名片，请打开原件")
            checkCancelled()
            incomplete = incomplete || reader.warnings.isNotEmpty()
            fun clean(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 4096) incomplete = true }?.take(4096)
            fun values(items: List<MmsContactValue>) = items.distinct().also { if (it.size > 32) incomplete = true }.take(32)
            val name = clean(card.formattedName?.value) ?: card.structuredName?.let {
                clean((listOfNotNull(it.family) + it.given.orEmpty() + it.additionalNames).filter(String::isNotBlank).joinToString(" "))
            }
            val photo = card.photos.firstOrNull()
            val photoData = photo?.data?.takeIf { it.size <= MAX_PHOTO_BYTES }
            if (photo?.data != null && photoData == null) incomplete = true
            val reference = photo?.url?.takeIf { value ->
                value.startsWith("cid:", true) || (!value.contains(':') && !value.startsWith('/') && !value.contains(".."))
            }
            if (photo?.url != null && reference == null) incomplete = true
            MmsContactModel(
                name = name,
                organization = clean(card.organization?.values?.joinToString(" · ")),
                phones = values(card.telephoneNumbers.mapNotNull { phone ->
                    clean(phone.text ?: phone.uri?.let { it.number + (it.extension?.let { ext -> " x$ext" } ?: "") })?.let {
                        MmsContactValue(it, phone.types.joinToString("/") { type -> type.value })
                    }
                }),
                emails = values(card.emails.mapNotNull { email -> clean(email.value)?.let { MmsContactValue(it, email.types.joinToString("/") { type -> type.value }) } }),
                addresses = values(card.addresses.mapNotNull { address ->
                    clean(address.label ?: listOf(address.poBox, address.extendedAddress, address.streetAddress, address.locality,
                        address.region, address.postalCode, address.country).filterNotNull().filter(String::isNotBlank).joinToString(" "))?.let {
                        MmsContactValue(it, address.types.joinToString("/") { type -> type.value })
                    }
                }),
                title = clean(card.titles.joinToString(" · ") { it.value.orEmpty() }),
                notes = clean(card.notes.joinToString("\n") { it.value.orEmpty() }),
                photoBytes = photoData, photoReference = reference,
                importWarning = if (incomplete || name == null) "部分字段、照片或姓名未完整读取，请核对原件" else null,
            )
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { failed("名片字段异常，请打开原件") }

    private fun failed(warning: String) = MmsContactModel(null, null, emptyList(), emptyList(), emptyList(), null, null, importWarning = warning)

    companion object {
        const val MAX_TEXT = 512 * 1024
        const val MAX_PHOTO_BYTES = 64 * 1024
        const val MAX_CONTACTS = 32
    }
}
