package vip.mystery0.pixel.text.data.source.mirror

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CopiedMirrorFile(val relativePath: String, val byteCount: Long, val sha256: String)

/** 源文件名仅作元数据；所有文件均使用随机名，且位于不参与备份的私有目录。 */
class MirrorAttachmentStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.noBackupFilesDir, "message-mirror").apply { mkdirs() }.canonicalFile

    fun file(relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "invalid_attachment_path" }
        val target = File(root, relativePath).canonicalFile
        require(target.parentFile == root) { "invalid_attachment_path" }
        return target
    }

    fun localUri(relativePath: String): String = Uri.fromFile(file(relativePath)).toString()
    fun exists(relativePath: String): Boolean = file(relativePath).isFile
    fun files(): List<String> = root.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
    fun delete(relativePath: String): Boolean = file(relativePath).let { !it.exists() || it.delete() }

    fun reserveCopy(localId: Long, revision: Long): String {
        val stem = "$localId-$revision-${UUID.randomUUID()}"
        activeFiles += "$stem.partial"
        activeFiles += "$stem.blob"
        return stem
    }

    fun releaseCopy(stem: String) {
        activeFiles -= "$stem.partial"
        activeFiles -= "$stem.blob"
    }

    fun isActive(relativePath: String): Boolean = relativePath in activeFiles

    suspend fun copy(localId: Long, revision: Long, partId: Long, stem: String): CopiedMirrorFile = withContext(Dispatchers.IO) {
        require(partId >= 0 && localId > 0 && revision > 0) { "invalid_attachment_key" }
        require(isActive("$stem.partial") && isActive("$stem.blob")) { "missing_copy_reservation" }
        val temporary = file("$stem.partial")
        val destination = file("$stem.blob")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val input = resolver.openInputStream(Uri.parse("content://mms/part/$partId"))
                ?: throw FileNotFoundException("null_attachment_stream")
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                    }
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                check(temporary.renameTo(destination)) { "attachment_rename_failed" }
            }
            CopiedMirrorFile(destination.name, total, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    companion object { private val activeFiles = ConcurrentHashMap.newKeySet<String>() }
}
