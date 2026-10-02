package vip.mystery0.pixel.text.mms.outgoing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.math.sqrt

/** 原件与转换副本分开；导入完成后不再依赖外部 URI 的临时授权。 */
class MmsAttachmentPreparer(
    private val context: Context,
    private val payloadStore: MmsPayloadStore,
) {
    suspend fun importAttachment(uri: Uri): MmsAttachment {
        var ownedResult: File? = null
        try {
            return withContext(Dispatchers.IO) {
                if (uri.scheme != "content" || uri.authority.isNullOrBlank()) throw MmsSendException("只支持通过系统选择器选择的附件")
                var original: File? = null
                try {
                    var name = "附件"
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameColumn >= 0) name = safeDisplayName(cursor.getString(nameColumn).orEmpty())
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn) && cursor.getLong(sizeColumn) > MmsPayloadStore.MAX_ORIGINAL_BYTES) {
                                throw MmsSendException("附件原件不能超过 32 MiB")
                            }
                        }
                    }
                    val declaredMime = normalizeMime(context.contentResolver.getType(uri))
                    original = context.contentResolver.openInputStream(uri)?.use { payloadStore.importOriginal(it) }
                        ?: throw MmsSendException("无法打开附件，请重新选择")
                    ownedResult = original
                    currentCoroutineContext().ensureActive()
                    val magicMime = sniffImageMime(original)
                    var mime = magicMime ?: declaredMime ?: "application/octet-stream"
                    if (mime.startsWith("image/") && mime != "image/svg+xml") {
                        mime = imageHeader(original).mime
                    }
                    currentCoroutineContext().ensureActive()
                    MmsAttachment(UUID.randomUUID().toString(), name, mime, original.path, original.length())
                } catch (exception: CancellationException) {
                    original?.delete()
                    throw exception
                } catch (exception: Exception) {
                    original?.delete()
                    if (exception is MmsSendException) throw exception
                    throw MmsSendException("无法读取附件，请检查文件后重新选择")
                }
            }
        } catch (exception: CancellationException) {
            // withContext 返回调度器时也可能丢弃结果，不能遗留刚创建的原件。
            ownedResult?.delete()
            throw exception
        }
    }

    /** 预算规划只给真正可转换的静态图均分余额，原件型附件先保留实际字节。 */
    fun isAdaptableImage(attachment: MmsAttachment): Boolean {
        val mime = attachment.originalMime
        if (!mime.startsWith("image/") || mime in setOf("image/svg+xml", "image/gif")) return false
        val file = payloadStore.resolveOwned(attachment.originalPath)
        val header = imageHeader(file)
        return !header.animated && !isAnimatedPng(file, mime)
    }

    suspend fun prepare(attachment: MmsAttachment, policy: MmsSendPolicy, budget: Int): MmsAttachment {
        var ownedResult: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val maxBytes = minOf(budget, policy.maxMessageBytes, MmsPayloadStore.MAX_PDU_BYTES)
                if (maxBytes <= 0) throw MmsSendException("正文和附件已超出彩信限额，请减少内容")
                val original = payloadStore.resolveOwned(attachment.originalPath)
                if (!original.isFile || original.length() != attachment.originalSize || original.length() > MmsPayloadStore.MAX_ORIGINAL_BYTES) {
                    throw MmsSendException("附件原件缺失或已改变，请重新选择")
                }
                var prepared: File? = null
                try {
                    currentCoroutineContext().ensureActive()
                    val mime = normalizeMime(attachment.originalMime) ?: throw MmsSendException("附件类型无效，请重新选择")
                    val encoded: ByteArray
                    val preparedMime: String
                    val note: String
                    if (mime.startsWith("image/") && mime != "image/svg+xml") {
                        val header = imageHeader(original)
                        val animated = header.animated || mime == "image/gif" || isAnimatedPng(original, mime)
                        if (animated) {
                            if (header.width > policy.maxImageWidth || header.height > policy.maxImageHeight || original.length() > maxBytes) {
                                throw MmsSendException("动图超出所选 SIM 的尺寸或大小限额，请移除或更换；不会自动转为静态图")
                            }
                            encoded = payloadStore.read(original.path, maxBytes)
                            preparedMime = header.mime
                            note = "保留动图原件，文件可能含原始元数据"
                        } else {
                            val result = encodeStaticImage(original, policy, maxBytes)
                            encoded = result.bytes
                            preparedMime = result.mime
                            note = result.note
                        }
                    } else {
                        if (original.length() > maxBytes) throw MmsSendException("音视频或文件超出本条彩信的附件预算，请移除或更换")
                        encoded = payloadStore.read(original.path, maxBytes)
                        if (mime == "application/smil" || mime == "text/plain" && !isLosslessInlineText(encoded)) {
                            preparedMime = "application/octet-stream"
                            note = "保留原件字节，按普通文件发送以避免文字编码损失或外部演示；文件可能含原始元数据"
                        } else {
                            preparedMime = mime
                            note = "按原件发送，文件可能含原始元数据；接收端未必支持此格式"
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    prepared = payloadStore.writePrepared(encoded)
                    ownedResult = prepared
                    currentCoroutineContext().ensureActive()
                    attachment.copy(preparedPath = prepared.path, preparedMime = preparedMime,
                        preparedSize = encoded.size.toLong(), preparedSha256 = payloadStore.sha256(encoded), preparationNote = note, error = null)
                } catch (exception: CancellationException) {
                    prepared?.delete()
                    throw exception
                } catch (exception: Exception) {
                    prepared?.delete()
                    if (exception is MmsSendException) throw exception
                    throw MmsSendException("无法准备附件，请更换文件后重试")
                }
            }
        } catch (exception: CancellationException) {
            ownedResult?.delete()
            throw exception
        }
    }

    private data class Header(val width: Int, val height: Int, val mime: String, val animated: Boolean)
    private class HeaderRead : RuntimeException()

    /** 只读系统解码器的头；在分配像素前退出，并统一验证像素上限。 */
    private fun imageHeader(file: File): Header {
        var header: Header? = null
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { _, info, _ ->
                checkPixels(info.size.width, info.size.height)
                header = Header(info.size.width, info.size.height, normalizeMime(info.mimeType)
                    ?: throw MmsSendException("图片格式无效"), info.isAnimated)
                throw HeaderRead()
            }
        } catch (_: HeaderRead) {
            return header ?: throw MmsSendException("无法读取图片尺寸")
        }
        throw MmsSendException("无法读取图片")
    }

    private data class EncodedImage(val bytes: ByteArray, val mime: String, val note: String)

    private suspend fun encodeStaticImage(file: File, policy: MmsSendPolicy, budget: Int): EncodedImage {
        var bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                checkPixels(info.size.width, info.size.height)
                if (info.isAnimated) throw MmsSendException("动图不能自动转为静态图")
                val scale = minOf(1.0, minOf(policy.maxImageWidth, MAX_DECODE_EDGE).toDouble() / info.size.width,
                    minOf(policy.maxImageHeight, MAX_DECODE_EDGE).toDouble() / info.size.height,
                    sqrt(MAX_DECODE_PIXELS.toDouble() / (info.size.width.toLong() * info.size.height)))
                decoder.setTargetSize(maxOf(1, floor(info.size.width * scale).toInt()), maxOf(1, floor(info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
        try {
            currentCoroutineContext().ensureActive()
            var flattened = false
            if (bitmap.hasAlpha()) {
                compressBounded(bitmap, Bitmap.CompressFormat.PNG, 100, budget)?.let {
                    return EncodedImage(it, "image/png", "已按所选 SIM 调整图片尺寸和方向，移除原始元数据，保留透明背景")
                }
                val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                } catch (exception: Exception) {
                    opaque.recycle()
                    throw exception
                }
                bitmap.recycle()
                bitmap = opaque
                flattened = true
            }
            // 质量和尺寸尝试都有固定上限，避免坏图或极小预算造成无限压缩循环。
            repeat(4) { round ->
                for (quality in intArrayOf(90, 80, 70, 60, 50)) {
                    currentCoroutineContext().ensureActive()
                    compressBounded(bitmap, Bitmap.CompressFormat.JPEG, quality, budget)?.let {
                        return EncodedImage(it, "image/jpeg", if (flattened) "已缩放并转为 JPEG，透明背景变为白色，原始元数据已移除" else "已按所选 SIM 调整图片尺寸、方向和质量，原始元数据已移除")
                    }
                }
                if (round == 3 || maxOf(bitmap.width, bitmap.height) <= 160) {
                    throw MmsSendException("图片压缩后仍超出预算，请减少附件或更换图片")
                }
                val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width * 3 / 4), maxOf(1, bitmap.height * 3 / 4), true)
                bitmap.recycle()
                bitmap = smaller
            }
            throw MmsSendException("图片超出彩信预算")
        } finally {
            bitmap.recycle()
        }
    }

    private class EncodeLimit : RuntimeException()
    private fun compressBounded(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int, limit: Int): ByteArray? {
        val output = object : ByteArrayOutputStream(minOf(limit, 32 * 1024)) {
            override fun write(value: Int) {
                if (count >= limit) throw EncodeLimit()
                super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (count.toLong() + length > limit) throw EncodeLimit()
                super.write(bytes, offset, length)
            }
        }
        return try {
            if (bitmap.compress(format, quality, output)) output.toByteArray().takeIf { it.isNotEmpty() } else null
        } catch (_: EncodeLimit) {
            null
        }
    }

    private fun checkPixels(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width.toLong() * height > 64_000_000L) throw MmsSendException("图片尺寸无效或超过 6400 万像素")
    }

    private fun sniffImageMime(file: File): String? {
        val bytes = ByteArray(16)
        val length = file.inputStream().use { it.read(bytes) }
        if (length >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return "image/jpeg"
        if (length >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))) return "image/png"
        if (length >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) return "image/gif"
        if (length >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return "image/webp"
        return null
    }

    /** Android 不保证呈现 APNG 动画，所以不能把它当普通 PNG 静态化。 */
    private fun isAnimatedPng(file: File, mime: String): Boolean {
        if (mime != "image/png") return false
        RandomAccessFile(file, "r").use { input ->
            input.seek(8)
            while (input.filePointer + 12 <= input.length()) {
                val length = input.readInt().toLong() and 0xffffffffL
                val type = input.readInt()
                if (length > input.length() - input.filePointer - 4) throw MmsSendException("PNG 图片结构不完整")
                if (type == 0x6163544c) return true // acTL
                if (type == 0x49454e44) return false // IEND
                input.seek(input.filePointer + length + 4)
            }
        }
        return false
    }

    private fun isLosslessInlineText(bytes: ByteArray): Boolean = try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        !text.contains('\u0000') && text.toByteArray(Charsets.UTF_8).contentEquals(bytes)
    } catch (_: java.nio.charset.CharacterCodingException) {
        false
    }

    private fun safeDisplayName(value: String): String = value.filter {
        !it.isISOControl() && it !in "\\/" && Character.getType(it) != Character.FORMAT.toInt()
    }.take(120).trim().ifEmpty { "附件" }

    private fun normalizeMime(value: String?): String? = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.length in 3..127 && it.matches(Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) }

    companion object {
        private const val MAX_DECODE_EDGE = 4096
        private const val MAX_DECODE_PIXELS = 4_000_000L
    }
}
