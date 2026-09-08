package vip.mystery0.pixel.text.data.source.mms

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPartKey
import vip.mystery0.pixel.text.domain.parser.mms.MmsMimeTypes
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository

data class SharedMmsAttachment(
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
)

enum class MmsAttachmentFailureReason {
    MESSAGE_UNAVAILABLE,
    PART_UNAVAILABLE,
    NOT_READY,
    SOURCE_MISSING,
    PERMISSION_DENIED,
    NO_SPACE,
    WRITE_FAILED,
}

class MmsAttachmentExportException(
    val reason: MmsAttachmentFailureReason,
    cause: Throwable? = null,
) : IOException(reason.name.lowercase(), cause)

/**
 * 附件动作始终按消息键和 partId 重新读取镜像，UI 中缓存的 URI 只用于展示。
 * 原件按字节复制；Provider 仅保留字符串的内联文本会明确导出为 UTF-8 副本。
 */
class MmsAttachmentExporter(
    context: Context,
    private val mirror: MessageMirrorRepository,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val mirrorRoot = File(appContext.noBackupFilesDir, "message-mirror").canonicalFile
    private val cacheRoot = appContext.cacheDir.canonicalFile
    private val shareRoot = File(cacheRoot, "mms-share")

    suspend fun export(part: MmsPartKey, destination: Uri) = withContext(Dispatchers.IO) {
        val source = resolveFresh(part)
        try {
            val output = resolver.openOutputStream(destination, "w")
                ?: throw MmsAttachmentExportException(MmsAttachmentFailureReason.WRITE_FAILED)
            output.use { writeSource(source, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MmsAttachmentExportException) {
            throw failure
        } catch (failure: Throwable) {
            throw classifyWriteFailure(failure)
        }
    }

    suspend fun prepareShare(part: MmsPartKey): SharedMmsAttachment = withContext(Dispatchers.IO) {
        var restrictedRoot: File? = null
        var directory: File? = null
        try {
            restrictedRoot = ensureShareRoot()
            cleanupExpiredShares(restrictedRoot)
            val source = resolveFresh(part)
            directory = File(restrictedRoot, UUID.randomUUID().toString()).canonicalFile
            require(directory.parentFile == restrictedRoot) { "invalid_share_path" }
            Files.createDirectory(directory.toPath())
            activeShareDirectories += directory.path
            val destination = File(directory, source.displayName).canonicalFile
            require(destination.parentFile == directory) { "invalid_share_path" }
            FileOutputStream(destination).use { output ->
                writeSource(source, output)
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            directory.setLastModified(System.currentTimeMillis())
            val uri = FileProvider.getUriForFile(
                appContext,
                "${BuildConfig.APPLICATION_ID}.mms.attachment",
                destination,
            )
            SharedMmsAttachment(uri, source.sharedMimeType, source.displayName)
        } catch (cancelled: CancellationException) {
            directory?.let { failedDirectory ->
                activeShareDirectories -= failedDirectory.path
                restrictedRoot?.let { deleteShareDirectory(failedDirectory, it) }
            }
            throw cancelled
        } catch (failure: MmsAttachmentExportException) {
            directory?.let { failedDirectory ->
                activeShareDirectories -= failedDirectory.path
                restrictedRoot?.let { deleteShareDirectory(failedDirectory, it) }
            }
            throw failure
        } catch (failure: Throwable) {
            directory?.let { failedDirectory ->
                activeShareDirectories -= failedDirectory.path
                restrictedRoot?.let { deleteShareDirectory(failedDirectory, it) }
            }
            throw classifyWriteFailure(failure)
        } finally {
            directory?.let { activeShareDirectories -= it.path }
        }
    }

    private fun ensureShareRoot(): File {
        Files.createDirectories(shareRoot.toPath())
        val canonical = shareRoot.canonicalFile
        if (canonical.parentFile != cacheRoot || !canonical.isDirectory) {
            throw IOException("invalid_share_root")
        }
        return canonical
    }

    private suspend fun resolveFresh(key: MmsPartKey): Source {
        currentCoroutineContext().ensureActive()
        if (key.message.transport != MessageTransport.MMS) {
            throw MmsAttachmentExportException(MmsAttachmentFailureReason.MESSAGE_UNAVAILABLE)
        }
        val message = mirror.getMessage(key.message)
            ?: throw MmsAttachmentExportException(MmsAttachmentFailureReason.MESSAGE_UNAVAILABLE)
        val part = message.parts.firstOrNull { it.sourceId == key.partId }
            ?: throw MmsAttachmentExportException(MmsAttachmentFailureReason.PART_UNAVAILABLE)
        val kind = MmsMimeTypes.classify(part.mimeType)
        val mimeType = MmsMimeTypes.normalize(part.mimeType)
        val displayName = safeMmsAttachmentName(
            preferredName = listOf(part.filename, part.name, part.contentLocation)
                .firstOrNull { !it.isNullOrBlank() },
            kind = kind,
            mimeType = mimeType,
            partId = key.partId,
        )
        val localUri = part.attachment?.localUri
        if (part.attachment?.state == MirrorAttachmentState.READY && localUri != null) {
            val file = localMirrorFile(localUri)
            return Source.FileSource(file, mimeType, displayName)
        }
        if (part.text != null && MmsMimeTypes.canExportInlineText(part.mimeType)) {
            return Source.InlineText(part.text, mimeType, displayName)
        }
        if (part.attachment?.state == MirrorAttachmentState.READY) {
            throw MmsAttachmentExportException(MmsAttachmentFailureReason.SOURCE_MISSING)
        }
        throw MmsAttachmentExportException(MmsAttachmentFailureReason.NOT_READY)
    }

    private fun localMirrorFile(localUri: String): File {
        val file = try {
            val uri = URI(localUri)
            if (!uri.scheme.equals("file", ignoreCase = true)) {
                throw MmsAttachmentExportException(MmsAttachmentFailureReason.SOURCE_MISSING)
            }
            File(uri).canonicalFile
        } catch (failure: MmsAttachmentExportException) {
            throw failure
        } catch (failure: Exception) {
            throw MmsAttachmentExportException(MmsAttachmentFailureReason.SOURCE_MISSING, failure)
        }
        if (file.parentFile != mirrorRoot || !file.isFile) {
            throw MmsAttachmentExportException(MmsAttachmentFailureReason.SOURCE_MISSING)
        }
        return file
    }

    private suspend fun writeSource(source: Source, output: OutputStream) {
        when (source) {
            is Source.FileSource -> try {
                source.file.inputStream().use { input -> copyBytes(input, output) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SecurityException) {
                throw MmsAttachmentExportException(MmsAttachmentFailureReason.PERMISSION_DENIED, failure)
            } catch (failure: FileNotFoundException) {
                throw MmsAttachmentExportException(MmsAttachmentFailureReason.SOURCE_MISSING, failure)
            }
            is Source.InlineText -> writeUtf8(source.text, output)
        }
    }

    private suspend fun copyBytes(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        currentCoroutineContext().ensureActive()
        output.flush()
    }

    private suspend fun writeUtf8(text: String, output: OutputStream) {
        val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
        var offset = 0
        while (offset < text.length) {
            currentCoroutineContext().ensureActive()
            val count = minOf(TEXT_CHUNK_SIZE, text.length - offset)
            writer.write(text, offset, count)
            offset += count
        }
        currentCoroutineContext().ensureActive()
        writer.flush()
    }

    private suspend fun cleanupExpiredShares(restrictedRoot: File) {
        currentCoroutineContext().ensureActive()
        val threshold = System.currentTimeMillis() - SHARE_MAX_AGE_MILLIS
        restrictedRoot.listFiles()?.forEach { directory ->
            currentCoroutineContext().ensureActive()
            val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile == restrictedRoot && canonical.path !in activeShareDirectories &&
                canonical.lastModified() < threshold) {
                deleteShareDirectory(canonical, restrictedRoot)
            }
        }
    }

    private fun deleteShareDirectory(directory: File, restrictedRoot: File) {
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (canonical.parentFile != restrictedRoot || canonical.path in activeShareDirectories) return
        canonical.listFiles()?.forEach { child ->
            if (runCatching { child.canonicalFile.parentFile == canonical }.getOrDefault(false)) child.delete()
        }
        canonical.delete()
    }

    private fun classifyWriteFailure(failure: Throwable): MmsAttachmentExportException {
        val causes = generateSequence(failure) { it.cause }.toList()
        val reason = when {
            causes.any { it is SecurityException } -> MmsAttachmentFailureReason.PERMISSION_DENIED
            causes.any { it is ErrnoException && it.errno == OsConstants.ENOSPC } ||
                causes.any { it.message?.contains("ENOSPC", ignoreCase = true) == true ||
                    it.message?.contains("no space left", ignoreCase = true) == true } ->
                MmsAttachmentFailureReason.NO_SPACE
            else -> MmsAttachmentFailureReason.WRITE_FAILED
        }
        return MmsAttachmentExportException(reason, failure)
    }

    private sealed interface Source {
        val sharedMimeType: String
        val displayName: String

        data class FileSource(
            val file: File,
            override val sharedMimeType: String,
            override val displayName: String,
        ) : Source

        data class InlineText(
            val text: String,
            override val sharedMimeType: String,
            override val displayName: String,
        ) : Source
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val TEXT_CHUNK_SIZE = 8 * 1024
        const val SHARE_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        val activeShareDirectories = ConcurrentHashMap.newKeySet<String>()
    }
}

fun suggestedMmsAttachmentName(part: MmsPartContent): String = safeMmsAttachmentName(
    preferredName = part.displayName.takeUnless { it == "附件 ${part.key.partId}" },
    kind = part.kind,
    mimeType = part.mimeType,
    partId = part.key.partId,
)

fun safeMmsAttachmentName(
    preferredName: String?,
    kind: MmsContentKind,
    mimeType: String,
    partId: Long,
): String {
    val leaf = preferredName.orEmpty().replace('\\', '/').substringAfterLast('/')
    val cleaned = buildString(leaf.length) {
        leaf.forEach { character ->
            when {
                Character.isISOControl(character) -> Unit
                character in INVALID_FILENAME_CHARACTERS -> append('_')
                else -> append(character)
            }
        }
    }.trim().trim('.').let(::truncateFileName)
    if (cleaned.isNotBlank()) return cleaned
    val label = when (kind) {
        MmsContentKind.TEXT -> "文本附件"
        MmsContentKind.IMAGE -> "图片附件"
        MmsContentKind.AUDIO -> "音频附件"
        MmsContentKind.VIDEO -> "视频附件"
        MmsContentKind.HTML -> "网页附件"
        MmsContentKind.CONTACT -> "联系人附件"
        MmsContentKind.CALENDAR -> "日历附件"
        MmsContentKind.SMIL -> "演示附件"
        MmsContentKind.MULTIPART -> "复合附件"
        MmsContentKind.FILE -> "附件"
    }
    return "$label-$partId${extensionForMimeType(mimeType)}"
}

private fun truncateFileName(value: String): String {
    if (value.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) return value
    // 系统文件名上限按 UTF-8 字节计算，保留常见扩展名，并在完整码点处截断。
    val extension = value.substringAfterLast('.', "").takeIf {
        it.isNotEmpty() && it.length <= 16 && it.all { character -> character.isLetterOrDigit() }
    }?.let { ".$it" }.orEmpty()
    val stem = if (extension.isEmpty()) value else value.dropLast(extension.length)
    val budget = MAX_FILENAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
    var offset = 0
    var bytes = 0
    while (offset < stem.length) {
        val codePoint = stem.codePointAt(offset)
        val count = Character.charCount(codePoint)
        val encodedSize = stem.substring(offset, offset + count).toByteArray(Charsets.UTF_8).size
        if (bytes + encodedSize > budget) break
        bytes += encodedSize
        offset += count
    }
    return stem.substring(0, offset) + extension
}

private fun extensionForMimeType(mimeType: String): String = when (MmsMimeTypes.normalize(mimeType)) {
    "text/plain" -> ".txt"
    "text/html" -> ".html"
    "application/xhtml+xml", "application/vnd.wap.xhtml+xml" -> ".xhtml"
    "image/jpeg" -> ".jpg"
    "image/png" -> ".png"
    "image/gif" -> ".gif"
    "image/webp" -> ".webp"
    "image/heic", "image/heif" -> ".heic"
    "image/avif" -> ".avif"
    "audio/mpeg" -> ".mp3"
    "audio/mp4", "audio/x-m4a" -> ".m4a"
    "audio/wav", "audio/x-wav" -> ".wav"
    "audio/ogg", "application/ogg", "application/x-ogg" -> ".ogg"
    "video/mp4" -> ".mp4"
    "video/3gpp" -> ".3gp"
    "text/vcard", "text/x-vcard", "application/vcard", "application/x-vcard" -> ".vcf"
    "text/calendar", "text/x-vcalendar", "text/vcalendar", "application/ics",
    "application/icalendar" -> ".ics"
    "application/smil", "application/smil+xml", "text/smil" -> ".smil"
    else -> ".bin"
}

private const val MAX_FILENAME_BYTES = 200
private val INVALID_FILENAME_CHARACTERS = setOf(':', '*', '?', '\"', '<', '>', '|')
