package vip.mystery0.pixel.text.data.backup

import android.content.Context
import androidx.core.net.toUri
import com.squareup.moshi.Moshi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** 只处理容器，数据库及设置语义由后续校验器验证。 */
class BackupArchiveCodec(private val context: Context) {
    private val adapter = Moshi.Builder().build().adapter(BackupManifest::class.java)
    private val allowed = setOf("manifest.json", "settings.json") + BACKUP_DATABASES.map { "databases/$it" }

    suspend fun describe(file: File, name: String): BackupEntry {
        backupRequire(name in allowed || name.matches(Regex("theme/[a-zA-Z0-9_-]+\\.webp")), "快照包含不允许的临时文件")
        return BackupEntry(name, file.length(), digest(file))
    }

    suspend fun export(directory: File, manifest: BackupManifest, uri: String, password: CharArray?) {
        val archive = File(directory, "output.zip")
        File(directory, "manifest.json").writeText(adapter.toJson(manifest))
        ZipFile(archive, password).use { zip ->
            (manifest.entries.map { it.name } + "manifest.json").forEach { name ->
                currentCoroutineContext().ensureActive()
                val parameters = ZipParameters().apply {
                    fileNameInZip = name
                    isEncryptFiles = password != null
                    if (password != null) {
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                zip.addFile(File(directory, name), parameters)
            }
        }
        backupRequire(archive.length() <= MAX_BACKUP_BYTES, "备份文件超过 4 GiB 限制")
        try {
            val output = context.contentResolver.openOutputStream(uri.toUri(), "wt")
                ?: throw BackupException("无法写入所选文件")
            output.use { archive.inputStream().use { input -> copyLimited(input, it, MAX_BACKUP_BYTES) } }
        } catch (error: Exception) {
            // SAF 不保证能够删除失败文件，页面统一提示检查目标文件。
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri.toUri()) }
            throw error
        }
    }

    suspend fun inspect(uri: String, password: CharArray?, directory: File): ValidatedBackup {
        val archive = File(directory, "input.zip")
        val input = context.contentResolver.openInputStream(uri.toUri()) ?: throw BackupException("无法读取所选文件")
        archive.outputStream().use { output -> input.use { copyLimited(it, output, MAX_BACKUP_BYTES) } }
        ZipFile(archive, password).use { zip ->
            val headers = zip.fileHeaders
            backupRequire(headers.size in 1..32)
            backupRequire(headers.map { it.fileName }.toSet().size == headers.size)
            val encryption = headers.map { it.isEncrypted }.toSet()
            backupRequire(encryption.size == 1, "备份条目的加密状态不一致")
            var total = 0L
            for (header in headers) {
                currentCoroutineContext().ensureActive()
                val name = header.fileName
                backupRequire(!header.isDirectory && (name in allowed || name.matches(Regex("theme/[a-zA-Z0-9_-]+\\.webp"))))
                if (header.isEncrypted) {
                    backupRequire(header.encryptionMethod == EncryptionMethod.AES &&
                        header.aesExtraDataRecord?.aesKeyStrength == AesKeyStrength.KEY_STRENGTH_256)
                }
                val limit = when {
                    name.endsWith(".json") -> 4L * 1024 * 1024
                    name.startsWith("theme/") -> 32L * 1024 * 1024
                    else -> MAX_BACKUP_BYTES
                }
                backupRequire(header.uncompressedSize in 0..minOf(limit, MAX_BACKUP_BYTES - total))
                backupRequire(directory.usableSpace > header.uncompressedSize + 16L * 1024 * 1024, "暂存空间不足")
                val target = File(directory, name)
                target.parentFile!!.mkdirs()
                total += zip.getInputStream(header).use { stream ->
                    target.outputStream().use { copyLimited(stream, it, minOf(limit, MAX_BACKUP_BYTES - total)) }
                }
            }
        }
        val manifestFile = File(directory, "manifest.json")
        backupRequire(manifestFile.isFile)
        val manifest = adapter.fromJson(manifestFile.readText()) ?: throw BackupException("缺少备份清单")
        backupRequire(manifest.format == "pixeltext-backup" && manifest.schemaVersion == 1, "不支持此备份格式版本")
        backupRequire(manifest.sections.isNotEmpty() && manifest.sections.distinct().size == manifest.sections.size)
        backupRequire(manifest.smsCount in 0..MAX_SMS_COUNT)
        val listed = manifest.entries.map { it.name }
        backupRequire(listed.size == listed.toSet().size && "manifest.json" !in listed)
        val actual = directory.walkTopDown().filter { it.isFile && it != archive && it != manifestFile }
            .map { it.relativeTo(directory).invariantSeparatorsPath }.toSet()
        backupRequire(actual == listed.toSet(), "备份条目与清单不一致")
        for (entry in manifest.entries) {
            val file = File(directory, entry.name)
            backupRequire(entry.name in actual && file.length() == entry.size && digest(file) == entry.sha256, "备份完整性校验失败")
        }
        archive.delete()
        return ValidatedBackup(directory, manifest)
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            backupRequire(total <= limit, "备份超出大小限制")
            output.write(buffer, 0, count)
        }
    }

    private suspend fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
