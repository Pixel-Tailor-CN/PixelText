package vip.mystery0.pixel.text.data.backup

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.domain.backup.BackupSummary

/** 删除批次与恢复开启使用同一把锁，防止检查后、删除前插入历史消息。 */
class RestoreSafetyCoordinator(private val context: Context) {
    private val prefs = context.getSharedPreferences("backup_operation", Context.MODE_PRIVATE)
    private val deletionGate = Mutex()
    val active: Boolean get() = prefs.getBoolean("active", false)
    private val history by lazy {
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(java.io.File(context.noBackupFilesDir, "backup-protected.db"), null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS historical(fingerprint TEXT PRIMARY KEY NOT NULL)")
        }
    }

    /** 先持久化历史消息身份再写 Provider，覆盖写入后、记账前进程死亡的窗口。 */
    suspend fun <T> insertHistorical(values: android.content.ContentValues, action: () -> T): T = deletionGate.withLock {
        check(active)
        history.execSQL("INSERT OR IGNORE INTO historical VALUES(?)", arrayOf(identity(values)))
        action()
    }

    /** 不重放历史消息的通知；恢复期间仅暂停新收信的自动操作，不阻断其通知。 */
    suspend fun liveMessageEffects(messageId: Long, action: suspend (allowAutoAction: Boolean) -> Unit) = deletionGate.withLock {
        if (active) {
            val uri = android.content.ContentUris.withAppendedId(android.provider.Telephony.Sms.CONTENT_URI, messageId)
            val protected = context.contentResolver.query(uri, arrayOf("address", "body", "date", "date_sent"), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) false else {
                    val values = android.content.ContentValues().also { android.database.DatabaseUtils.cursorRowToContentValues(cursor, it) }
                    history.rawQuery("SELECT 1 FROM historical WHERE fingerprint=?", arrayOf(identity(values))).use { it.moveToFirst() }
                }
            } ?: false
            if (protected) return@withLock
        }
        action(!active)
    }

    private fun identity(values: android.content.ContentValues): String {
        fun part(value: String?) = if (value == null) "-1:" else "${value.length}:$value"
        val raw = "${part(values.getAsString("address"))}${part(values.getAsString("body"))}:${values.getAsLong("date") ?: 0}:${values.getAsLong("date_sent") ?: 0}"
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    suspend fun begin() = deletionGate.withLock {
        if (!active) history.execSQL("DELETE FROM historical")
        backupRequire(prefs.edit().putBoolean("active", true).commit(), "无法保存恢复保护状态")
    }
    suspend fun finish() = deletionGate.withLock {
        backupRequire(prefs.edit().clear().commit(), "无法结束恢复保护，请重试")
        history.execSQL("DELETE FROM historical")
    }
    suspend fun cleanupBatch(action: suspend () -> Int): Int = deletionGate.withLock {
        if (active) 0 else action()
    }
    fun record(summary: BackupSummary) {
        backupRequire(prefs.edit().putLong("inserted", summary.inserted).putLong("existing", summary.existing)
            .putLong("remaining", summary.remaining).putLong("failed", summary.failed)
            .putLong("converted", summary.convertedOutgoing).putLong("skipped", summary.skippedAssociations).putStringSet("completed", summary.completedSections.map { it.name }.toSet()).commit(),
            "恢复进度保存失败，已写入的短信会保留")
    }
    fun summary(): BackupSummary = BackupSummary(
        inserted = prefs.getLong("inserted", 0), existing = prefs.getLong("existing", 0),
        remaining = prefs.getLong("remaining", 0), failed = prefs.getLong("failed", 0),
        convertedOutgoing = prefs.getLong("converted", 0), skippedAssociations = prefs.getLong("skipped", 0),
        completedSections = prefs.getStringSet("completed", emptySet()).orEmpty().mapNotNull {
            runCatching { vip.mystery0.pixel.text.domain.backup.BackupSection.valueOf(it) }.getOrNull()
        }.toSet(),
    )
}
