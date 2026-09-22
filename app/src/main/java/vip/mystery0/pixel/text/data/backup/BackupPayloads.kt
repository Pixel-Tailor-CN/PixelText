package vip.mystery0.pixel.text.data.backup

import com.squareup.moshi.JsonClass
import vip.mystery0.pixel.text.domain.backup.BackupSection
import vip.mystery0.pixel.text.domain.theme.ThemeConfiguration
import java.io.File

@JsonClass(generateAdapter = true)
data class BackupEntry(val name: String, val size: Long, val sha256: String)
@JsonClass(generateAdapter = true)
data class BackupManifest(
    val format: String = "pixeltext-backup", val schemaVersion: Int = 1,
    val appVersion: String, val createdAt: Long, val smsSyncedAt: Long?,
    val sections: List<BackupSection>, val smsCount: Long,
    val mirrorVersion: Int = 3, val spamVersion: Int = 3, val archiveVersion: Int = 1,
    val entries: List<BackupEntry>,
)
@JsonClass(generateAdapter = true)
data class PortablePreference(val key: String, val type: String, val value: String)
@JsonClass(generateAdapter = true)
data class BackupSettings(
    val preferences: List<PortablePreference>, val theme: ThemeConfiguration,
    val includeNormal: Boolean, val includeSpam: Boolean, val includeArchived: Boolean,
)
data class ValidatedBackup(val directory: File, val manifest: BackupManifest)

internal const val MAX_BACKUP_BYTES = 4L * 1024 * 1024 * 1024
internal const val MAX_SMS_COUNT = 1_000_000L
internal const val MAX_SMS_BYTES = 1024 * 1024
internal val BACKUP_DATABASES = listOf("message_mirror.db", "spam.db", "conversation_archive.db")
internal fun backupRequire(condition: Boolean, message: String = "备份文件无效或不受支持") {
    if (!condition) throw BackupException(message)
}
internal class BackupException(message: String) : Exception(message)
