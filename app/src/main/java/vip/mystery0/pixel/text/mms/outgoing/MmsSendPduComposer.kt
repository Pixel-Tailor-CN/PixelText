package vip.mystery0.pixel.text.mms.outgoing

import android.content.Context
import vip.mystery0.pixel.text.R
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
            ?: throw MmsSendException(context.getString(R.string.sys_mms_pdu_single_recipient_only))
        if (recipient != snapshot.recipientAddress || snapshot.subscriptionId != snapshot.policy.subscriptionId || limit <= 0) {
            throw MmsSendException(context.getString(R.string.sys_mms_pdu_info_changed))
        }
        if (!transactionId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) throw MmsSendException(context.getString(R.string.sys_mms_pdu_invalid_tx_id))
        if (snapshot.body.isBlank() && snapshot.attachments.isEmpty()) throw MmsSendException(context.getString(R.string.sys_mms_pdu_subject_only_disallowed))
        if (snapshot.subject.length > snapshot.policy.maxSubjectLength) throw MmsSendException(context.getString(R.string.sys_mms_pdu_subject_too_long))
        if (snapshot.attachments.size > MmsPayloadStore.MAX_ATTACHMENTS ||
            snapshot.attachments.map { it.id }.distinct().size != snapshot.attachments.size ||
            snapshot.attachments.any { it.originalSize !in 1..MmsPayloadStore.MAX_ORIGINAL_BYTES } ||
            snapshot.attachments.sumOf { it.originalSize } > MmsPayloadStore.MAX_DRAFT_ORIGINAL_BYTES
        ) throw MmsSendException(context.getString(R.string.sys_mms_pdu_attachments_over_limit))
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
            val path = attachment.preparedPath ?: throw MmsSendException(context.getString(R.string.sys_mms_pdu_attachment_not_prepared))
            val mime = attachment.preparedMime ?: throw MmsSendException(context.getString(R.string.sys_mms_pdu_attachment_mime_unconfirmed))
            if (!mime.matches(Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) || mime.length > 127 ||
                mime.startsWith("multipart/") || mime.startsWith("application/vnd.wap.multipart") || mime == "application/smil"
            ) throw MmsSendException(context.getString(R.string.sys_mms_pdu_container_disallowed))
            val expectedSize = attachment.preparedSize ?: throw MmsSendException(context.getString(R.string.sys_mms_pdu_attachment_size_unconfirmed))
            if (attachment.error != null || expectedSize <= 0 || expectedSize > limit - contentBytes) throw MmsSendException(context.getString(R.string.sys_mms_pdu_attachment_not_ready_or_over_limit))
            val bytes = payloadStore.read(path, (limit - contentBytes).toInt())
            if (bytes.size.toLong() != expectedSize || payloadStore.sha256(bytes) != attachment.preparedSha256) {
                throw MmsSendException(context.getString(R.string.sys_mms_pdu_attachment_changed))
            }
            if (mime == "text/plain" && (bytes.any { it == 0.toByte() } ||
                    !String(bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(bytes))) {
                throw MmsSendException(context.getString(R.string.sys_mms_pdu_plain_text_encoding_unconfirmed))
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
        if (contentBytes >= limit) throw MmsSendException(context.getString(R.string.sys_mms_pdu_full_exceeds_sim_limit_reduce))
        val contentType = if (related) RELATED else MIXED
        val request = SendReq().apply {
            setTransactionId(transactionId.toByteArray(Charsets.US_ASCII))
            setContentType(contentType.toByteArray(Charsets.US_ASCII))
            from = EncodedStringValue(CharacterSets.UTF_8, PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR.toByteArray(Charsets.US_ASCII))
            to = arrayOf(EncodedStringValue(CharacterSets.UTF_8, recipient.toByteArray(Charsets.US_ASCII)))
            date = snapshot.createdAt / 1000
            deliveryReport = PduHeaders.VALUE_NO
            readReport = PduHeaders.VALUE_NO
            if (subjectBytes.isNotEmpty()) subject = EncodedStringValue(CharacterSets.UTF_8, subjectBytes)
            body = PduBody().apply {
                parts.forEach { part ->
                    addPart(PduPart().apply {
                        setContentType(part.mimeType.toByteArray(Charsets.US_ASCII))
                        setContentId(part.contentId.toByteArray(Charsets.US_ASCII))
                        setContentLocation(part.contentLocation.toByteArray(Charsets.US_ASCII))
                        setName(part.contentLocation.toByteArray(Charsets.US_ASCII))
                        if (part.charset != 0) charset = part.charset
                        setData(part.bytes)
                    })
                }
            }
        }
        val bytes = try {
            PduComposer(context, request, limit).make() ?: throw MmsSendException(context.getString(R.string.sys_mms_pdu_encode_failed))
        } catch (_: IllegalArgumentException) {
            throw MmsSendException(context.getString(R.string.sys_mms_pdu_exceeds_limit_or_invalid))
        }
        if (bytes.isEmpty() || bytes.size > limit) throw MmsSendException(context.getString(R.string.sys_mms_pdu_full_exceeds_sim_limit))
        verify(bytes, request, parts)
        return ComposedMms(bytes, parts.toList(), contentType)
    }

    private fun verify(bytes: ByteArray, expected: SendReq, parts: List<MmsPayloadPart>) {
        val parsed = try { PduParser(bytes, true).parse() as? SendReq } catch (_: RuntimeException) { null }
            ?: throw MmsSendException(context.getString(R.string.sys_mms_pdu_verify_failed))
        fun fail(): Nothing = throw MmsSendException(context.getString(R.string.sys_mms_pdu_verify_mismatch))
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
        if (limit < 0 || value.length > limit || value.contains('\u0000')) throw MmsSendException(context.getString(R.string.sys_mms_pdu_text_limit_or_invalid_chars))
        return try {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = ByteBuffer.allocate(minOf(limit.toLong(), value.length.toLong() * 3).toInt())
            val result = encoder.encode(CharBuffer.wrap(value), buffer, true)
            if (result.isError) result.throwException()
            if (result.isOverflow) throw MmsSendException(context.getString(R.string.sys_mms_pdu_text_exceeds_sim_bytes))
            buffer.flip()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw MmsSendException(context.getString(R.string.sys_mms_pdu_text_incomplete_chars))
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
