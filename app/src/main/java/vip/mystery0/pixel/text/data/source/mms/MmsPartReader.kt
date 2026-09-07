package vip.mystery0.pixel.text.data.source.mms

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mirror.MirrorPartModel
import vip.mystery0.pixel.text.domain.parser.mms.MmsMimeTypes
import vip.mystery0.pixel.text.mms.vendor.pdu.CharacterSets

/** 仅打开镜像本地文件；可注入流工厂供合成样例验收使用。 */
class MmsPartReader(private val openLocalStream: (String) -> InputStream? = { File(URI(it)).inputStream() }) {
    companion object {
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    }

    /** 一次消息解析共用预算，包含失败读取实际消耗的字节；不跨消息复用。 */
    class Budget {
        var remaining: Int = MAX_TOTAL_BYTES
            private set

        internal fun consume(count: Int) { remaining -= count }
    }

    data class TextResult(val text: String?, val byteCount: Long?, val issue: String?)

    suspend fun readText(part: MirrorPartModel, budget: Budget): TextResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        part.text?.let { inline ->
            // Provider 已解码的字符串无需按声明再次编码；按 UTF-8 字节计费，避免巨型临时数组。
            val count = inlineUtf8Size(inline, minOf(MAX_TEXT_BYTES, budget.remaining))
            if (count == null) return@withContext TextResult(null, part.attachment?.byteCount, "too_large")
            if (count < 0) return@withContext TextResult(null, part.attachment?.byteCount, "invalid_encoding")
            budget.consume(count)
            return@withContext TextResult(inline.removePrefix("\uFEFF"), part.attachment?.byteCount ?: count.toLong(), null)
        }
        val attachment = part.attachment
        if (attachment?.state != MirrorAttachmentState.READY || attachment.localUri == null) {
            return@withContext TextResult(null, attachment?.byteCount, issueFor(part))
        }
        val limit = minOf(MAX_TEXT_BYTES, budget.remaining)
        if (limit == 0 || (attachment.byteCount ?: 0) > limit) {
            return@withContext TextResult(null, attachment.byteCount, "too_large")
        }
        try {
            // 当前镜像附件均为 file URI，拒绝远程或其他来源，避免内容解析触网。
            require(URI(attachment.localUri).scheme.equals("file", ignoreCase = true))
            val bytes = openLocalStream(attachment.localUri)?.use { input ->
                val output = ByteArrayOutputStream(minOf(limit, 8192))
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // 最多探测一个额外字节；探测本身也计入整条消息的读取预算。
                    val remaining = limit - output.size()
                    val allowed = minOf(buffer.size, remaining + 1, budget.remaining)
                    if (allowed == 0) {
                        // 没有预算探测 EOF 时仅信任镜像复制时记录的精确长度。
                        if (attachment.byteCount == output.size().toLong()) break
                        return@withContext TextResult(null, attachment.byteCount, "too_large")
                    }
                    val count = input.read(buffer, 0, allowed)
                    currentCoroutineContext().ensureActive()
                    if (count < 0) break
                    if (count == 0) continue
                    budget.consume(count)
                    if (count > remaining) return@withContext TextResult(null, attachment.byteCount, "too_large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: return@withContext TextResult(null, attachment.byteCount, "read_failed")
            currentCoroutineContext().ensureActive()
            decode(bytes, part).also { currentCoroutineContext().ensureActive() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            TextResult(null, attachment.byteCount, "read_failed")
        }
    }

    private fun decode(bytes: ByteArray, part: MirrorPartModel): TextResult {
        val bom = when {
            bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> "UTF-32BE" to 4
            bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> "UTF-32LE" to 4
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> "UTF-8" to 3
            bytes.startsWith(0xFE, 0xFF) -> "UTF-16BE" to 2
            bytes.startsWith(0xFF, 0xFE) -> "UTF-16LE" to 2
            else -> null
        }
        val declared = try {
            val name = MmsMimeTypes.charsetName(part.mimeType) ?: part.charset?.takeIf { it != 0 }?.let {
                if (it == CharacterSets.UCS2) "UTF-16BE" else CharacterSets.getMimeName(it)
            }
            name?.let(Charset::forName)
        } catch (_: Exception) {
            return TextResult(null, bytes.size.toLong(), "unsupported_charset")
        }
        val bomCharset = bom?.let { Charset.forName(it.first) }
        if (declared != null && bomCharset != null && declared != bomCharset &&
            !(declared.name() == "UTF-16" && bom.first.startsWith("UTF-16")) &&
            !(declared.name() == "UTF-32" && bom.first.startsWith("UTF-32"))) {
            return TextResult(null, bytes.size.toLong(), "invalid_encoding")
        }
        val charset = bomCharset ?: declared ?: Charsets.UTF_8
        return try {
            val offset = bom?.second ?: 0
            val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
            TextResult(text, bytes.size.toLong(), null)
        } catch (_: CharacterCodingException) {
            TextResult(null, bytes.size.toLong(), "invalid_encoding")
        }
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean = size >= prefix.size &&
        prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

    private suspend fun inlineUtf8Size(text: String, limit: Int): Int? {
        var count = 0
        var index = 0
        while (index < text.length) {
            if (index % 4096 == 0) currentCoroutineContext().ensureActive()
            val char = text[index++]
            count += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> { index++; 4 }
                char.isSurrogate() -> return -1
                else -> 3
            }
            if (count > limit) return null
        }
        return count
    }

    fun issueFor(part: MirrorPartModel): String? = when (part.attachment?.state) {
        MirrorAttachmentState.PENDING_DOWNLOAD -> "pending_download"
        MirrorAttachmentState.SOURCE_PRESENT -> "source_present"
        MirrorAttachmentState.COPYING -> "copying"
        MirrorAttachmentState.COPY_FAILED -> "copy_failed"
        MirrorAttachmentState.SOURCE_UNREADABLE -> "source_unreadable"
        MirrorAttachmentState.READY -> if (part.attachment.localUri == null) "unavailable" else null
        else -> "unavailable"
    }
}
