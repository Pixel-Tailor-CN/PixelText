package vip.mystery0.pixel.text.domain.parser.mms

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import vip.mystery0.pixel.text.mms.vendor.pdu.CharacterSets

/** Provider 写入与附件读取共用字符集和 BOM 规则，避免正文在落库时提前乱码。 */
object MmsTextDecoder {
    data class Result(val text: String?, val byteCount: Long, val issue: String?)

    fun decode(bytes: ByteArray, mimeType: String?, charsetId: Int?): Result {
        val bom = when {
            bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> "UTF-32BE" to 4
            bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> "UTF-32LE" to 4
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> "UTF-8" to 3
            bytes.startsWith(0xFE, 0xFF) -> "UTF-16BE" to 2
            bytes.startsWith(0xFF, 0xFE) -> "UTF-16LE" to 2
            else -> null
        }
        val declared = try {
            val name = MmsMimeTypes.charsetName(mimeType) ?: charsetId?.takeIf { it != 0 }?.let {
                if (it == CharacterSets.UCS2) "UTF-16BE" else CharacterSets.getMimeName(it)
            }
            name?.let(Charset::forName)
        } catch (_: Exception) {
            return Result(null, bytes.size.toLong(), "unsupported_charset")
        }
        val bomCharset = bom?.let { Charset.forName(it.first) }
        if (declared != null && bomCharset != null && declared != bomCharset &&
            !(declared.name() == "UTF-16" && bom.first.startsWith("UTF-16")) &&
            !(declared.name() == "UTF-32" && bom.first.startsWith("UTF-32"))) {
            return Result(null, bytes.size.toLong(), "invalid_encoding")
        }
        val charset = bomCharset ?: declared ?: Charsets.UTF_8
        return try {
            val offset = bom?.second ?: 0
            val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
            Result(text, bytes.size.toLong(), null)
        } catch (_: CharacterCodingException) {
            Result(null, bytes.size.toLong(), "invalid_encoding")
        }
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean = size >= prefix.size &&
        prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

}
