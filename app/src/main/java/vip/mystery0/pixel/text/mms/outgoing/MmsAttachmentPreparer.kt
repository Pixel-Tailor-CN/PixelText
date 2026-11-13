package vip.mystery0.pixel.text.mms.outgoing

import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import java.util.concurrent.atomic.AtomicReference
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
import vip.mystery0.pixel.text.R
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
        val ownedResult = AtomicReference<File?>()
        try {
            return withContext(Dispatchers.IO) {
                if (uri.scheme != "content" || uri.authority.isNullOrBlank()) throw MmsSendException(context.getString(R.string.sys_mms_attach_picker_only))
                var original: File? = null
                try {
                    var name = context.getString(R.string.sys_mms_attach_default_name)
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameColumn >= 0) name = safeDisplayName(cursor.getString(nameColumn).orEmpty())
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn) && cursor.getLong(sizeColumn) > MmsPayloadStore.MAX_ORIGINAL_BYTES) {
                                throw MmsSendException(context.getString(R.string.sys_mms_attach_original_too_large))
                            }
                        }
                    }
                    val declaredMime = normalizeMime(context.contentResolver.getType(uri))
                    original = context.contentResolver.openInputStream(uri)?.use { payloadStore.importOriginal(it) }
                        ?: throw MmsSendException(context.getString(R.string.sys_mms_attach_cannot_open))
                    ownedResult.set(original)
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
                    throw MmsSendException(context.getString(R.string.sys_mms_attach_cannot_read))
                }
            }
        } catch (exception: CancellationException) {
            // withContext 返回调度器时也可能丢弃结果，不能遗留刚创建的原件。
            ownedResult.get()?.delete()
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
        val ownedResult = AtomicReference<File?>()
        try {
            return withContext(Dispatchers.IO) {
                val maxBytes = minOf(budget, policy.maxMessageBytes, MmsPayloadStore.MAX_PDU_BYTES)
                if (maxBytes <= 0) throw MmsSendException(context.getString(R.string.sys_mms_attach_limit_exceeded))
                val original = payloadStore.resolveOwned(attachment.originalPath)
                if (!original.isFile || original.length() != attachment.originalSize || original.length() > MmsPayloadStore.MAX_ORIGINAL_BYTES) {
                    throw MmsSendException(context.getString(R.string.sys_mms_attach_missing_or_changed))
                }
                var prepared: File? = null
                try {
                    currentCoroutineContext().ensureActive()
                    val mime = normalizeMime(attachment.originalMime) ?: throw MmsSendException(context.getString(R.string.sys_mms_attach_invalid_type))
                    val encoded: ByteArray
                    val preparedMime: String
                    val note: String
                    if (mime.startsWith("image/") && mime != "image/svg+xml") {
                        val header = imageHeader(original)
                        val animated = header.animated || mime == "image/gif" || isAnimatedPng(original, mime)
                        if (animated) {
                            if (header.width > policy.maxImageWidth || header.height > policy.maxImageHeight || original.length() > maxBytes) {
                                throw MmsSendException(context.getString(R.string.sys_mms_attach_animated_exceeds_limit))
                            }
                            encoded = payloadStore.read(original.path, maxBytes)
                            preparedMime = header.mime
                            note = context.getString(R.string.sys_mms_attach_note_animated_original)
                        } else {
                            val result = encodeStaticImage(original, policy, maxBytes)
                            encoded = result.bytes
                            preparedMime = result.mime
                            note = result.note
                        }
                    } else {
                        if (original.length() > maxBytes) throw MmsSendException(context.getString(R.string.sys_mms_attach_media_exceeds_budget))
                        encoded = payloadStore.read(original.path, maxBytes)
                        if (mime == "application/smil" || mime == "text/plain" && !isLosslessInlineText(encoded)) {
                            preparedMime = "application/octet-stream"
                            note = context.getString(R.string.sys_mms_attach_note_plain_file_fallback)
                        } else {
                            preparedMime = mime
                            note = context.getString(R.string.sys_mms_attach_note_original_generic)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    prepared = payloadStore.writePrepared(encoded)
                    ownedResult.set(prepared)
                    currentCoroutineContext().ensureActive()
                    attachment.copy(preparedPath = prepared.path, preparedMime = preparedMime,
                        preparedSize = encoded.size.toLong(), preparedSha256 = payloadStore.sha256(encoded), preparationNote = note, error = null)
                } catch (exception: CancellationException) {
                    prepared?.delete()
                    throw exception
                } catch (exception: Exception) {
                    prepared?.delete()
                    if (exception is MmsSendException) throw exception
                    throw MmsSendException(context.getString(R.string.sys_mms_attach_prepare_failed))
                }
            }
        } catch (exception: CancellationException) {
            ownedResult.get()?.delete()
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
                    ?: throw MmsSendException(context.getString(R.string.sys_mms_attach_invalid_image_format)), info.isAnimated)
                throw HeaderRead()
            }
        } catch (_: HeaderRead) {
            return header ?: throw MmsSendException(context.getString(R.string.sys_mms_attach_cannot_read_image_size))
        }
        throw MmsSendException(context.getString(R.string.sys_mms_attach_cannot_read_image))
    }

    private class EncodedImage(val bytes: ByteArray, val mime: String, val note: String)

    private suspend fun encodeStaticImage(file: File, policy: MmsSendPolicy, budget: Int): EncodedImage {
        var bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                checkPixels(info.size.width, info.size.height)
                if (info.isAnimated) throw MmsSendException(context.getString(R.string.sys_mms_attach_animated_no_static_convert))
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
                    return EncodedImage(it, "image/png", context.getString(R.string.sys_mms_attach_note_png_resized))
                }
                val opaque = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
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
                        return EncodedImage(
                            it,
                            "image/jpeg",
                            context.getString(
                                if (flattened) R.string.sys_mms_attach_note_jpeg_flattened
                                else R.string.sys_mms_attach_note_jpeg_resized
                            )
                        )
                    }
                }
                if (round == 3 || maxOf(bitmap.width, bitmap.height) <= 160) {
                    throw MmsSendException(context.getString(R.string.sys_mms_attach_image_still_too_large))
                }
                val smaller = bitmap.scale(maxOf(1, bitmap.width * 3 / 4), maxOf(1, bitmap.height * 3 / 4))
                bitmap.recycle()
                bitmap = smaller
            }
            throw MmsSendException(context.getString(R.string.sys_mms_attach_image_exceeds_budget))
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
        if (width <= 0 || height <= 0 || width.toLong() * height > 64_000_000L) throw MmsSendException(context.getString(R.string.sys_mms_attach_image_pixels_exceeded))
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
                if (length > input.length() - input.filePointer - 4) throw MmsSendException(context.getString(R.string.sys_mms_attach_png_corrupt))
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
    }.take(120).trim().ifEmpty { context.getString(R.string.sys_mms_attach_default_name) }

    private fun normalizeMime(value: String?): String? = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.length in 3..127 && it.matches(Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) }

    companion object {
        private const val MAX_DECODE_EDGE = 4096
        private const val MAX_DECODE_PIXELS = 4_000_000L
    }
}
