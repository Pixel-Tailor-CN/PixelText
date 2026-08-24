package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.theme.ThemeAssetRepository
import vip.mystery0.pixel.text.domain.theme.ThemeImageDraft
import vip.mystery0.pixel.text.domain.theme.ThemeImageReference
import vip.mystery0.pixel.text.domain.theme.ThemeMode
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ThemeAssetRepositoryImpl(
    context: Context,
) : ThemeAssetRepository {
    private val appContext = context.applicationContext
    private val assetsDir: File =
        File(appContext.filesDir, ASSETS_DIR_NAME).also { it.mkdirs() }
    private val draftsDir: File =
        File(appContext.cacheDir, DRAFTS_DIR_NAME).also { it.mkdirs() }

    override suspend fun createDraftBackground(
        mode: ThemeMode,
        sourceUri: String,
    ): Result<ThemeImageDraft> {
        var publishedFile: File? = null
        return try {
            withContext(Dispatchers.IO) {
                try {
                    val draftId = UUID.randomUUID().toString()
                    require(isSafeId(draftId)) {
                        "invalid draft id draft_id=$draftId"
                    }
                    val draftFile = draftFile(draftId)
                    decodeAndCompressToWebp(sourceUri, draftFile)
                    publishedFile = draftFile
                    Result.success(ThemeImageDraft(draftId = draftId, mode = mode))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    publishedFile?.delete()
                    publishedFile = null
                    Log.w(
                        TAG,
                        "theme image decode failed uri=$sourceUri error=${error.message}",
                    )
                    Result.failure(error)
                }
            }
        } catch (error: CancellationException) {
            cleanupPublishedFile(publishedFile)
            throw error
        }
    }

    override suspend fun commitDraft(draft: ThemeImageDraft): Result<ThemeImageReference> {
        var publishedFile: File? = null
        return try {
            withContext(Dispatchers.IO) {
                try {
                    val draftFile = resolve(draft)
                        ?: error("draft file missing draft_id=${draft.draftId}")
                    val assetId = buildAssetId(draft.mode)
                    require(isSafeId(assetId)) {
                        "invalid asset id asset_id=$assetId"
                    }
                    val finalFile = assetFile(assetId)
                    val tempFile = File(
                        assetsDir,
                        "$assetId.tmp-${UUID.randomUUID()}.webp",
                    )
                    try {
                        draftFile.copyTo(tempFile, overwrite = true)
                        if (finalFile.exists() && !finalFile.delete()) {
                            error("failed to replace existing asset asset_id=$assetId")
                        }
                        if (!tempFile.renameTo(finalFile)) {
                            error("failed to publish asset atomically asset_id=$assetId")
                        }
                        publishedFile = finalFile
                    } catch (error: Throwable) {
                        tempFile.delete()
                        throw error
                    }
                    // Keep the draft until theme JSON save succeeds so callers can retry.
                    Result.success(ThemeImageReference(assetId = assetId))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    publishedFile?.delete()
                    publishedFile = null
                    Log.w(
                        TAG,
                        "theme image commit failed draft_id=${draft.draftId} error=${error.message}",
                    )
                    Result.failure(error)
                }
            }
        } catch (error: CancellationException) {
            cleanupPublishedFile(publishedFile)
            throw error
        }
    }

    override fun discardDraft(draft: ThemeImageDraft) {
        val file = resolve(draft) ?: return
        if (!file.delete() && file.exists()) {
            Log.w(TAG, "theme draft delete failed draft_id=${draft.draftId}")
        }
    }

    override fun deleteAsset(reference: ThemeImageReference) {
        val file = resolve(reference) ?: return
        if (!file.delete() && file.exists()) {
            Log.w(TAG, "theme asset delete failed asset_id=${reference.assetId}")
        }
    }

    override fun resolve(reference: ThemeImageReference): File? {
        val assetId = reference.assetId
        if (!isSafeId(assetId)) {
            Log.w(TAG, "theme asset resolve rejected asset_id=$assetId")
            return null
        }
        return resolveInside(assetsDir, assetFileName(assetId))
    }

    override fun resolve(draft: ThemeImageDraft): File? {
        val draftId = draft.draftId
        if (!isSafeId(draftId)) {
            Log.w(TAG, "theme draft resolve rejected draft_id=$draftId")
            return null
        }
        return resolveInside(draftsDir, draftFileName(draftId))
    }

    override suspend fun cleanStaleDrafts() {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - STALE_DRAFT_AGE_MILLIS
            val files = draftsDir.listFiles() ?: return@withContext
            files.forEach { file ->
                if (!file.isFile) {
                    return@forEach
                }
                val lastModified = file.lastModified()
                if (lastModified > 0L && lastModified < cutoff) {
                    if (!file.delete() && file.exists()) {
                        Log.w(TAG, "theme stale draft delete failed path=${file.name}")
                    }
                }
            }
        }
    }

    private suspend fun cleanupPublishedFile(file: File?) {
        if (file == null) {
            return
        }
        withContext(NonCancellable + Dispatchers.IO) {
            if (!file.delete() && file.exists()) {
                Log.w(TAG, "theme published file cleanup failed path=${file.name}")
            }
        }
    }

    private fun decodeAndCompressToWebp(sourceUri: String, outputFile: File) {
        val uri = sourceUri.toUri()
        val source = ImageDecoder.createSource(appContext.contentResolver, uri)
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val maxEdge = maxOf(info.size.width, info.size.height)
                val sample = (maxEdge / MAX_BACKGROUND_EDGE_PX).coerceAtLeast(1)
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            bitmap = decoded
            val limited = limitMaxEdge(decoded)
            if (limited !== decoded) {
                scaled = limited
            }
            val tempOutput = File(
                outputFile.parentFile,
                "${outputFile.name}.tmp-${UUID.randomUUID()}",
            )
            try {
                FileOutputStream(tempOutput).use { stream ->
                    val compressed = limited.compress(
                        Bitmap.CompressFormat.WEBP_LOSSY,
                        WEBP_QUALITY,
                        stream,
                    )
                    if (!compressed) {
                        error("webp compress failed")
                    }
                }
                if (outputFile.exists() && !outputFile.delete()) {
                    error("failed to replace draft output")
                }
                if (!tempOutput.renameTo(outputFile)) {
                    error("failed to publish draft output atomically")
                }
            } catch (error: Throwable) {
                tempOutput.delete()
                throw error
            }
        } finally {
            scaled?.let { extra ->
                if (extra !== bitmap) {
                    extra.recycle()
                }
            }
            bitmap?.recycle()
        }
    }

    private fun limitMaxEdge(bitmap: Bitmap): Bitmap {
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        if (maxEdge <= MAX_BACKGROUND_EDGE_PX) {
            return bitmap
        }
        val scale = MAX_BACKGROUND_EDGE_PX.toFloat() / maxEdge.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(width, height)
    }

    private fun buildAssetId(mode: ThemeMode): String {
        val modeToken = when (mode) {
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
        }
        return "conversation_detail_${modeToken}_${UUID.randomUUID()}"
    }

    private fun draftFile(draftId: String): File {
        return File(draftsDir, draftFileName(draftId))
    }

    private fun assetFile(assetId: String): File {
        return File(assetsDir, assetFileName(assetId))
    }

    private fun draftFileName(draftId: String): String = "draft_$draftId.webp"

    private fun assetFileName(assetId: String): String = "$assetId.webp"

    private fun resolveInside(directory: File, fileName: String): File? {
        if (fileName.isEmpty() || fileName.contains('/') || fileName.contains('\\')) {
            return null
        }
        if (fileName.contains("..")) {
            return null
        }
        val expectedDir = directory.canonicalFile.also { it.mkdirs() }
        val candidate = File(expectedDir, fileName).canonicalFile
        val parent = candidate.parentFile ?: return null
        if (parent != expectedDir) {
            Log.w(TAG, "theme path escape rejected name=$fileName")
            return null
        }
        if (!candidate.exists() || !candidate.isFile) {
            return null
        }
        return candidate
    }

    private fun isSafeId(id: String): Boolean {
        return id.isNotEmpty() && SAFE_ID.matches(id)
    }

    private companion object {
        private const val TAG = "ThemeAssetRepository"
        private const val ASSETS_DIR_NAME = "theme_assets"
        private const val DRAFTS_DIR_NAME = "theme_drafts"
        private const val MAX_BACKGROUND_EDGE_PX = 2160
        private const val WEBP_QUALITY = 88
        private const val STALE_DRAFT_AGE_MILLIS = 24L * 60 * 60 * 1000
        private val SAFE_ID = Regex("[a-zA-Z0-9_-]+")
    }
}
