package vip.mystery0.pixel.text.data.source.mms

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent

/** 只接受本地 URI，内容标识、SMIL src 和文件名都不能作为播放地址。 */
fun localMmsUri(value: String?): Uri? = value?.toUri()?.takeIf {
    (it.scheme == "file" && it.authority.isNullOrEmpty() && it.path?.startsWith('/') == true) ||
        (it.scheme == "content" && !it.authority.isNullOrEmpty())
}

fun MmsPartContent.mediaCacheKey(): String =
    "${key.message.transport}:${key.message.sourceId}:${key.partId}:$contentHash:$revision:$byteCount:$localUri"

/** 仅包含有界派生预览，不代替附件业务模型。 */
data class MmsMediaPreview(val durationMillis: Long? = null, val thumbnail: Bitmap? = null, val error: String? = null)

class MmsMediaMetadataReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)
    private val cache = object : LruCache<String, MmsMediaPreview>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: MmsMediaPreview) = (value.thumbnail?.allocationByteCount ?: 0) + 512
    }

    suspend fun read(part: MmsPartContent): MmsMediaPreview = withContext(dispatcher) {
        val key = part.mediaCacheKey()
        // 缺少内容哈希时不缓存，以免同一路径原件更新后展示旧缩略图。
        if (part.contentHash != null) cache.get(key)?.let { return@withContext it }
        val uri = localMmsUri(part.localUri) ?: return@withContext MmsMediaPreview(error = "附件尚不可读")
        if ((part.byteCount ?: 0) > MAX_SOURCE_BYTES) return@withContext MmsMediaPreview(error = "文件较大，进入查看页播放")
        val preview = try {
            currentCoroutineContext().ensureActive()
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                val length = descriptor.declaredLength.takeIf { it >= 0 } ?: descriptor.parcelFileDescriptor.statSize
                if (length !in 1..MAX_SOURCE_BYTES) return@use MmsMediaPreview(error = "无法在预览预算内读取媒体")
                MediaMetadataRetriever().use { reader ->
                    reader.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, length)
                    val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    val thumbnail = if (part.kind == MmsContentKind.VIDEO) {
                        reader.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
                    } else null
                    currentCoroutineContext().ensureActive()
                    MmsMediaPreview(durationMillis = duration, thumbnail = thumbnail)
                }
            } ?: MmsMediaPreview(error = "附件尚不可读")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MmsMediaPreview(error = "无法读取预览，可尝试播放或保存原件")
        }
        if (part.contentHash != null) cache.put(key, preview)
        preview
    }

    private companion object { const val MAX_SOURCE_BYTES = 64L * 1024 * 1024 }
}
