package vip.mystery0.pixel.text.data.source.mms

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mirror.MirrorPartModel
import vip.mystery0.pixel.text.domain.model.mms.usesInlineTextCopy
import vip.mystery0.pixel.text.domain.parser.mms.MmsTextDecoder

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
        part.text?.takeIf { part.usesInlineTextCopy() }?.let { inline ->
            // Provider 已解码的字符串无需按声明再次编码；按 UTF-8 字节计费，避免巨型临时数组。
            val result = inlineUtf8Size(inline, budget)
            if (result.issue != null) return@withContext TextResult(null, null, result.issue)
            return@withContext TextResult(inline.removePrefix("\uFEFF"), result.byteCount.toLong(), null)
        }
        val result = readBytes(part, budget)
        result.bytes?.let { bytes ->
            val decoded = MmsTextDecoder.decode(bytes, part.mimeType, part.charset)
            TextResult(decoded.text, decoded.byteCount, decoded.issue)
        } ?: TextResult(null, result.byteCount, result.issue)
    }

    data class BytesResult(val bytes: ByteArray?, val byteCount: Long?, val issue: String?)

    /** 容器与文本共享本次消息读取预算；原件过大时保留 URI，不做截断解析。 */
    suspend fun readBytes(part: MirrorPartModel, budget: Budget): BytesResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val attachment = part.attachment
        if (attachment?.state != MirrorAttachmentState.READY || attachment.localUri == null) {
            return@withContext BytesResult(null, attachment?.byteCount, issueFor(part))
        }
        val limit = minOf(MAX_TEXT_BYTES, budget.remaining)
        if (limit == 0 || (attachment.byteCount ?: 0) > limit) {
            return@withContext BytesResult(null, attachment.byteCount, "too_large")
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
                        return@withContext BytesResult(null, attachment.byteCount, "too_large")
                    }
                    val count = input.read(buffer, 0, allowed)
                    currentCoroutineContext().ensureActive()
                    if (count < 0) break
                    if (count == 0) continue
                    budget.consume(count)
                    if (count > remaining) return@withContext BytesResult(null, attachment.byteCount, "too_large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: return@withContext BytesResult(null, attachment.byteCount, "read_failed")
            currentCoroutineContext().ensureActive()
            BytesResult(bytes, bytes.size.toLong(), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            BytesResult(null, attachment.byteCount, "read_failed")
        }
    }

    private data class InlineSizeResult(val byteCount: Int, val issue: String?)

    private suspend fun inlineUtf8Size(text: String, budget: Budget): InlineSizeResult {
        var count = 0
        var index = 0
        while (index < text.length) {
            if (index % 4096 == 0) currentCoroutineContext().ensureActive()
            // 字符串长度已知，无需越界探测；耗尽预算后不再读取下一个字符。
            val available = minOf(MAX_TEXT_BYTES - count, budget.remaining)
            if (available == 0) return InlineSizeResult(count, "too_large")
            val char = text[index++]
            val pairedSurrogate = char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate()
            val width = when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                pairedSurrogate -> 4
                else -> 3
            }
            // 失败路径同样扣费；损坏代理位按三字节处理预算计费，不生成替换正文。
            val consumed = minOf(width, available)
            budget.consume(consumed)
            count += consumed
            if (consumed < width) return InlineSizeResult(count, "too_large")
            if (char.isSurrogate() && !pairedSurrogate) return InlineSizeResult(count, "invalid_encoding")
            if (pairedSurrogate) index++
        }
        return InlineSizeResult(count, null)
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
