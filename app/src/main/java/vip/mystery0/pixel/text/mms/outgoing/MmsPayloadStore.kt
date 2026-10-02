package vip.mystery0.pixel.text.mms.outgoing

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** 附件不进入缓存；只有独立 PDU 目录可经 FileProvider 对外授权。 */
class MmsPayloadStore(context: Context) {
    private val attachments = File(context.noBackupFilesDir, "mms-outgoing").canonicalFile
    private val pduDirectory = File(context.filesDir, "mms-send-pdu").canonicalFile
    // 当前进程写入但尚未入库的文件不能当孤儿；重启后只有数据库引用继续保护。
    private val currentProcessFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun importOriginal(input: InputStream): File {
        val directory = directory(attachments)
        val target = File(directory, "original-${UUID.randomUUID()}.bin")
        val temporary = File(directory, "${target.name}.partial")
        currentProcessFiles.add(target.absolutePath); currentProcessFiles.add(temporary.absolutePath)
        try {
            var total = 0L
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > MAX_ORIGINAL_BYTES) throw MmsSendException("附件原件不能超过 32 MiB")
                    if (directory.usableSpace < DISK_RESERVE_BYTES) throw MmsSendException("存储空间不足，请清理后重新选择附件")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw MmsSendException("附件为空，请重新选择")
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            if (!temporary.renameTo(target)) throw MmsSendException("无法保存附件，请重试")
            return target
        } finally {
            temporary.delete()
        }
    }

    fun writePrepared(bytes: ByteArray): File {
        if (bytes.isEmpty() || bytes.size > MAX_PDU_BYTES) throw MmsSendException("附件副本大小无效")
        return atomicWrite(directory(attachments), "prepared-${UUID.randomUUID()}.bin", bytes)
    }

    /** 同一个 attempt 的 PDU 不允许被不同字节覆盖。 */
    @Synchronized
    fun writePdu(token: String, bytes: ByteArray): File {
        if (!token.matches(Regex("[A-Za-z0-9_-]{1,100}")) || bytes.isEmpty() || bytes.size > MAX_PDU_BYTES) {
            throw MmsSendException("彩信发送载荷无效")
        }
        val directory = directory(pduDirectory)
        val target = File(directory, "$token.pdu")
        currentProcessFiles.add(target.absolutePath)
        if (target.exists()) {
            if (!read(target.path, MAX_PDU_BYTES).contentEquals(bytes)) throw MmsSendException("彩信载荷身份不一致，请重新编辑")
            return target
        }
        return atomicWrite(directory, target.name, bytes)
    }

    fun resolveOwned(path: String): File {
        val file = File(path)
        val canonical = file.canonicalFile
        val parent = canonical.parentFile
        if (!file.isAbsolute || file.absoluteFile != canonical ||
            (parent != attachments.canonicalFile && parent != pduDirectory.canonicalFile) ||
            canonical.name.endsWith(".partial")
        ) throw MmsSendException("附件路径无效，请重新选择")
        return canonical
    }

    fun file(path: String): File = resolveOwned(path)

    fun read(path: String, maxBytes: Int): ByteArray {
        if (maxBytes !in 1..MAX_ORIGINAL_BYTES.toInt()) throw MmsSendException("附件读取预算无效")
        val file = resolveOwned(path)
        if (!file.isFile || file.length() > maxBytes) throw MmsSendException("附件不存在或已超出发送限额")
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream(minOf(file.length().toInt(), 64 * 1024))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size().toLong() + count > maxBytes) throw MmsSendException("附件超出发送限额")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun deleteOwned(path: String): Boolean {
        val file = resolveOwned(path)
        return !file.exists() || file.delete()
    }

    /** 每次有界扫描旧进程留下的文件；新进程在文件创建前注册本地租约。 */
    fun orphanCandidates(): List<String> = sequenceOf(attachments, pduDirectory).flatMap { directory ->
        directory.listFiles()?.asSequence() ?: emptySequence()
    }.filter { file -> file.isFile && file.absolutePath !in currentProcessFiles && file.absoluteFile == file.canonicalFile }
        .take(1000).map { it.absolutePath }.toList()

    fun deleteOrphan(path: String): Boolean {
        val file = File(path)
        val parent = file.parentFile?.canonicalFile ?: return false
        if (path in currentProcessFiles || file.absoluteFile != file.canonicalFile ||
            parent !in setOf(attachments.canonicalFile, pduDirectory.canonicalFile)) return false
        return !file.exists() || file.delete()
    }

    private fun directory(file: File): File {
        if ((!file.isDirectory && !file.mkdirs()) || file.canonicalFile != file.absoluteFile) {
            throw MmsSendException("无法创建彩信私有存储")
        }
        return file
    }

    private fun atomicWrite(directory: File, name: String, bytes: ByteArray): File {
        if (directory.usableSpace < bytes.size.toLong() + DISK_RESERVE_BYTES) throw MmsSendException("存储空间不足，请清理后重试")
        val target = File(directory, name)
        val temporary = File(directory, ".$name-${UUID.randomUUID()}.partial")
        currentProcessFiles.add(target.absolutePath); currentProcessFiles.add(temporary.absolutePath)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw MmsSendException("无法保存彩信载荷，请重试")
            return target
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val MAX_ATTACHMENTS = 10
        const val MAX_ORIGINAL_BYTES = 32L * 1024 * 1024
        const val MAX_DRAFT_ORIGINAL_BYTES = 64L * 1024 * 1024
        const val MAX_PDU_BYTES = 10 * 1024 * 1024
        private const val DISK_RESERVE_BYTES = 8L * 1024 * 1024
    }
}
