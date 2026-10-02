package vip.mystery0.pixel.text.mms.outgoing

import android.content.Context
import vip.mystery0.pixel.text.mms.vendor.pdu.CharacterSets
import vip.mystery0.pixel.text.mms.vendor.pdu.EncodedStringValue
import vip.mystery0.pixel.text.mms.vendor.pdu.PduBody
import vip.mystery0.pixel.text.mms.vendor.pdu.PduComposer
import vip.mystery0.pixel.text.mms.vendor.pdu.PduHeaders
import vip.mystery0.pixel.text.mms.vendor.pdu.PduParser
import vip.mystery0.pixel.text.mms.vendor.pdu.PduPart
import vip.mystery0.pixel.text.mms.vendor.pdu.SendReq
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** 同一份 part 字节同时供 PDU 和 Provider 使用，不在提交时重新转换附件。 */
class MmsSendPduComposer(
    private val context: Context,
    private val payloadStore: MmsPayloadStore,
) {
    fun compose(snapshot: MmsSendSnapshot, transactionId: String): ComposedMms {
        val limit = minOf(snapshot.policy.maxMessageBytes, MmsPayloadStore.MAX_PDU_BYTES)
        val recipient = MmsRecipient.normalize(snapshot.recipientAddress)
            ?: throw MmsSendException("彩信仅支持一个明确的电话号码")
        if (recipient != snapshot.recipientAddress || snapshot.subscriptionId != snapshot.policy.subscriptionId || limit <= 0) {
            throw MmsSendException("彩信发送信息已改变，请重新确认")
        }
        if (!transactionId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) throw MmsSendException("彩信事务标识无效")
        if (snapshot.body.isBlank() && snapshot.attachments.isEmpty()) throw MmsSendException("请添加正文或附件，不能只发送主题")
        if (snapshot.subject.length > snapshot.policy.maxSubjectLength) throw MmsSendException("主题超过所选 SIM 的长度限制")
        if (snapshot.attachments.size > MmsPayloadStore.MAX_ATTACHMENTS ||
            snapshot.attachments.map { it.id }.distinct().size != snapshot.attachments.size ||
            snapshot.attachments.any { it.originalSize !in 1..MmsPayloadStore.MAX_ORIGINAL_BYTES } ||
            snapshot.attachments.sumOf { it.originalSize } > MmsPayloadStore.MAX_DRAFT_ORIGINAL_BYTES
        ) throw MmsSendException("附件数量或原件总量超过限制")
        val bodyBytes = utf8(snapshot.body, minOf(snapshot.policy.maxTextBytes, limit))
        val subjectBytes = utf8(snapshot.subject, limit)
        val parts = mutableListOf<MmsPayloadPart>()
        val identity = payloadStore.sha256(buildString {
            append(snapshot.createdAt).append(':').append(snapshot.subscriptionId).append(':').append(recipient)
            append(':').append(payloadStore.sha256(bodyBytes)).append(':').append(payloadStore.sha256(subjectBytes))
            snapshot.attachments.forEach { append(':').append(it.id).append(':').append(it.preparedSha256) }
        }.toByteArray(Charsets.UTF_8)).take(20)
        if (bodyBytes.isNotEmpty()) parts += MmsPayloadPart("<pt-$identity-body>", "body.txt", "text/plain", bodyBytes, CharacterSets.UTF_8)
        var contentBytes = bodyBytes.size.toLong() + subjectBytes.size
        snapshot.attachments.forEachIndexed { index, attachment ->
            val path = attachment.preparedPath ?: throw MmsSendException("附件尚未准备完成")
            val mime = attachment.preparedMime ?: throw MmsSendException("附件类型尚未确认")
            if (!mime.matches(Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) || mime.length > 127 ||
                mime.startsWith("multipart/") || mime.startsWith("application/vnd.wap.multipart") || mime == "application/smil"
            ) throw MmsSendException("此附件容器格式不能直接发送，请选择原始文件")
            val expectedSize = attachment.preparedSize ?: throw MmsSendException("附件大小尚未确认")
            if (attachment.error != null || expectedSize <= 0 || expectedSize > limit - contentBytes) throw MmsSendException("附件尚未就绪或超出彩信限额")
            val bytes = payloadStore.read(path, (limit - contentBytes).toInt())
            if (bytes.size.toLong() != expectedSize || payloadStore.sha256(bytes) != attachment.preparedSha256) {
                throw MmsSendException("附件内容已改变，请重新选择")
            }
            if (mime == "text/plain" && (bytes.any { it == 0.toByte() } ||
                    !String(bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(bytes))) {
                throw MmsSendException("纯文本附件编码未确认，请重新准备附件")
            }
            contentBytes += bytes.size
            parts += MmsPayloadPart("<pt-$identity-part-$index>", "attachment-${index + 1}.${extension(mime)}", mime, bytes, if (mime == "text/plain") CharacterSets.UTF_8 else 0)
        }
        val related = parts.any { mediaTag(it.mimeType) != null }
        if (related) {
            val smil = smil(parts).toByteArray(Charsets.UTF_8)
            contentBytes += smil.size
            parts.add(0, MmsPayloadPart("<pt-$identity-smil>", "presentation.smil", "application/smil", smil, CharacterSets.UTF_8))
        }
        if (contentBytes >= limit) throw MmsSendException("完整彩信超出所选 SIM 的发送限额，请减少内容")
        val contentType = if (related) RELATED else MIXED
        val request = SendReq().apply {
            setTransactionId(transactionId.toByteArray(Charsets.US_ASCII))
            setContentType(contentType.toByteArray(Charsets.US_ASCII))
            setFrom(EncodedStringValue(CharacterSets.UTF_8, PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR.toByteArray(Charsets.US_ASCII)))
            setTo(arrayOf(EncodedStringValue(CharacterSets.UTF_8, recipient.toByteArray(Charsets.US_ASCII))))
            setDate(snapshot.createdAt / 1000)
            setDeliveryReport(PduHeaders.VALUE_NO)
            setReadReport(PduHeaders.VALUE_NO)
            if (subjectBytes.isNotEmpty()) setSubject(EncodedStringValue(CharacterSets.UTF_8, subjectBytes))
            setBody(PduBody().apply {
                parts.forEach { part ->
                    addPart(PduPart().apply {
                        setContentType(part.mimeType.toByteArray(Charsets.US_ASCII))
                        setContentId(part.contentId.toByteArray(Charsets.US_ASCII))
                        setContentLocation(part.contentLocation.toByteArray(Charsets.US_ASCII))
                        setName(part.contentLocation.toByteArray(Charsets.US_ASCII))
                        if (part.charset != 0) setCharset(part.charset)
                        setData(part.bytes)
                    })
                }
            })
        }
        val bytes = try {
            PduComposer(context, request, limit).make() ?: throw MmsSendException("无法编码彩信，请检查内容")
        } catch (exception: IllegalArgumentException) {
            throw MmsSendException("完整彩信超出限额或编码无效，请减少内容")
        }
        if (bytes.isEmpty() || bytes.size > limit) throw MmsSendException("完整彩信超出所选 SIM 的发送限额")
        verify(bytes, request, parts)
        return ComposedMms(bytes, parts.toList(), contentType)
    }

    private fun verify(bytes: ByteArray, expected: SendReq, parts: List<MmsPayloadPart>) {
        val parsed = try { PduParser(bytes, true).parse() as? SendReq } catch (_: RuntimeException) { null }
            ?: throw MmsSendException("彩信编码校验失败，尚未发送")
        fun fail(): Nothing = throw MmsSendException("彩信内容校验不一致，尚未发送")
        if (parsed.messageType != PduHeaders.MESSAGE_TYPE_SEND_REQ || parsed.mmsVersion != expected.mmsVersion ||
            !parsed.transactionId.contentEquals(expected.transactionId) || !parsed.contentType.contentEquals(expected.contentType) ||
            parsed.from?.string != PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR || parsed.to?.size != 1 ||
            parsed.to?.singleOrNull()?.string != expected.to.single().string || !parsed.cc.isNullOrEmpty() || !parsed.bcc.isNullOrEmpty() ||
            parsed.date != expected.date || parsed.deliveryReport != PduHeaders.VALUE_NO || parsed.readReport != PduHeaders.VALUE_NO ||
            !(parsed.subject?.textString ?: byteArrayOf()).contentEquals(expected.subject?.textString ?: byteArrayOf()) ||
            (parsed.subject != null && parsed.subject.characterSet != CharacterSets.UTF_8) || parsed.body?.partsNum != parts.size
        ) fail()
        parts.forEachIndexed { index, expectedPart ->
            val part = parsed.body.getPart(index)
            if (part.children != null || part.charset != expectedPart.charset ||
                !part.contentType.contentEquals(expectedPart.mimeType.toByteArray(Charsets.US_ASCII)) ||
                !part.contentId.contentEquals(expectedPart.contentId.toByteArray(Charsets.US_ASCII)) ||
                !part.contentLocation.contentEquals(expectedPart.contentLocation.toByteArray(Charsets.US_ASCII)) ||
                !part.data.contentEquals(expectedPart.bytes)
            ) fail()
        }
    }

    private fun utf8(value: String, limit: Int): ByteArray {
        if (limit < 0 || value.length > limit || value.contains('\u0000')) throw MmsSendException("正文或主题超过限制，或包含不支持的字符")
        return try {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = ByteBuffer.allocate(minOf(limit.toLong(), value.length.toLong() * 3).toInt())
            val result = encoder.encode(CharBuffer.wrap(value), buffer, true)
            if (result.isError) result.throwException()
            if (result.isOverflow) throw MmsSendException("正文或主题超过所选 SIM 的字节限制")
            buffer.flip()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (exception: java.nio.charset.CharacterCodingException) {
            throw MmsSendException("正文或主题包含不完整字符，请重新编辑")
        }
    }

    private fun smil(parts: List<MmsPayloadPart>): String = buildString {
        append("<smil><head><layout><root-layout width=\"320px\" height=\"480px\"/>")
        append("<region id=\"Image\" left=\"0\" top=\"0\" width=\"320px\" height=\"360px\" fit=\"meet\"/>")
        append("<region id=\"Text\" left=\"0\" top=\"360px\" width=\"320px\" height=\"120px\"/></layout></head><body>")
        parts.forEach { part ->
            val tag = if (part.contentLocation == "body.txt") "text" else mediaTag(part.mimeType)
            if (tag != null) {
                append(if (tag == "text" || tag == "img") "<par dur=\"5000ms\">" else "<par>")
                append('<').append(tag).append(" src=\"").append(part.contentLocation).append('"')
                if (tag != "audio") append(" region=\"").append(if (tag == "text") "Text" else "Image").append('"')
                append("/></par>")
            }
        }
        append("</body></smil>")
    }

    private fun mediaTag(mime: String): String? = when {
        mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/heif", "image/heic", "image/avif") -> "img"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> null
    }

    private fun extension(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heif", "image/heic" -> "heic"
        "image/avif" -> "avif"
        "image/bmp" -> "bmp"
        "audio/amr" -> "amr"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "video/3gpp" -> "3gp"
        "video/mp4" -> "mp4"
        "text/vcard", "text/x-vcard" -> "vcf"
        "text/calendar" -> "ics"
        "text/plain" -> "txt"
        "application/pdf" -> "pdf"
        else -> "bin"
    }

    companion object {
        const val RELATED = "application/vnd.wap.multipart.related"
        const val MIXED = "application/vnd.wap.multipart.mixed"
    }
}
