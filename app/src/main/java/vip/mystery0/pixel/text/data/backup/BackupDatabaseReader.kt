package vip.mystery0.pixel.text.data.backup

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.domain.backup.BackupSection
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistMatcher
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType
import java.io.File

/** v1 只接受发布时的三个明确版本；不对未知库执行自动迁移。 */
class BackupDatabaseReader(private val snapshots: AppDatabaseSnapshotter) {
    suspend fun validate(backup: ValidatedBackup) {
        val manifest = backup.manifest
        backupRequire(manifest.mirrorVersion == 3 && manifest.spamVersion == 3 && manifest.archiveVersion == 1,
            "此备份的数据库版本不受支持，请升级应用")
        var smsCount = 0L
        var ruleBytes = 0L
        for (definition in snapshots.definitions) {
            val file = File(backup.directory, "databases/${definition.name}")
            val smsSelected = BackupSection.SMS in manifest.sections
            val needed = if (definition.name == "spam.db") smsSelected || BackupSection.RULES in manifest.sections else smsSelected
            backupRequire(file.isFile == needed, "数据库与备份类别不一致")
            if (!needed) continue
            open(file).use { db ->
                backupRequire(db.version == definition.version)
                db.rawQuery("PRAGMA quick_check", null).use { backupRequire(it.moveToFirst() && it.getString(0) == "ok", "数据库已损坏") }
                val tables = mutableSetOf<String>()
                db.rawQuery("SELECT type,name,sql FROM sqlite_master", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val type = cursor.getString(0)
                        val name = cursor.getString(1)
                        if (name == "sqlite_sequence" && type == "table") continue
                        if (name == "android_metadata" && type == "table" && normalize(cursor.getString(2)) == "CREATE TABLE android_metadata (locale TEXT)") continue
                        if (type == "index" && name.startsWith("sqlite_autoindex_") && cursor.isNull(2)) continue
                        if (definition.name == "message_mirror.db" && type == "index") {
                            val expectedIndex = when (name) {
                                "backup_sms_source" -> "CREATE UNIQUE INDEX backup_sms_source ON mirror_message(sourceId)"
                                "backup_sms_thread" -> "CREATE INDEX backup_sms_thread ON mirror_message(threadId)"
                                else -> null
                            }
                            if (expectedIndex != null && normalize(cursor.getString(2)) == expectedIndex) continue
                        }
                        backupRequire(type == "table" && name in definition.tables, "数据库包含不允许的结构")
                        val expected = tableSql(definition.database.openHelper.readableDatabase, name)
                        backupRequire(normalize(expected) == normalize(cursor.getString(2)), "数据库结构不匹配")
                        tables += name
                    }
                }
                backupRequire(tables == definition.tables.toSet())
                db.rawQuery("PRAGMA foreign_key_check", null).use { backupRequire(!it.moveToFirst(), "数据库关联不完整") }
                for (table in definition.tables) {
                    var count = 0L
                    db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            backupRequire(++count <= MAX_SMS_COUNT)
                            when (table) {
                                "mirror_message" -> {
                                    backupRequire(cursor.stringOrNull("transport") == "SMS" && cursor.longOrNull("structureComplete") == 1L)
                                    backupRequire(cursor.longOrNull("localId")!! > 0 && cursor.longOrNull("sourceId")!! > 0)
                                    backupRequire(cursor.longOrNull("originalDate") != null && cursor.longOrNull("boxType") in 1L..6L)
                                    backupRequire(cursor.longOrNull("read") in 0L..1L && cursor.longOrNull("seen") in 0L..1L)
                                    smsCount++
                                }
                                "mirror_sms" -> {
                                    var bytes = 0L
                                    for (column in listOf("body", "address", "subject", "rawSnapshot")) {
                                        bytes += cursor.stringOrNull(column)?.toByteArray(Charsets.UTF_8)?.size ?: 0
                                    }
                                    backupRequire(bytes <= MAX_SMS_BYTES, "单条短信超出备份限制")
                                }
                                "blocked_keyword" -> {
                                    backupRequire(BackupSection.RULES in manifest.sections)
                                    val keyword = cursor.stringOrNull("keyword").orEmpty()
                                    ruleBytes += keyword.toByteArray(Charsets.UTF_8).size * 2L
                                    backupRequire(keyword.isNotBlank() && keyword.length <= MAX_SMS_BYTES && count <= 100_000 && ruleBytes <= 4L * 1024 * 1024, "关键词数据超过备份限制")
                                    backupRequire(cursor.stringOrNull("normalized_keyword") == keyword.trim().lowercase(java.util.Locale.ROOT))
                                }
                                "sender_whitelist_rule" -> {
                                    backupRequire(BackupSection.RULES in manifest.sections && count <= SenderWhitelistMatcher.MAX_RULES)
                                    val type = runCatching { WhitelistRuleType.valueOf(cursor.stringOrNull("type").orEmpty()) }.getOrNull()
                                    backupRequire(type != null && SenderWhitelistMatcher.validate(type, cursor.stringOrNull("value").orEmpty()) == null)
                                }
                                "spam_allowed_message" -> backupRequire(smsSelected && cursor.longOrNull("message_id")!! > 0)
                                "archived_conversation" -> backupRequire(smsSelected)
                            }
                        }
                    }
                }
                if (definition.name == "message_mirror.db") {
                    db.rawQuery("SELECT COUNT(*) FROM mirror_sms", null).use { backupRequire(it.moveToFirst() && it.getLong(0) == smsCount) }
                    db.rawQuery("SELECT sourceId FROM mirror_message GROUP BY sourceId HAVING COUNT(*)>1 LIMIT 1", null).use { backupRequire(!it.moveToFirst(), "备份消息来源 ID 重复") }
                }
            }
        }
        backupRequire(smsCount == manifest.smsCount, "短信数量与备份清单不一致")
    }

    fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).apply {
        execSQL("PRAGMA trusted_schema=OFF")
    }
    private fun normalize(sql: String): String = sql.replace(Regex("(?i)IF\\s+NOT\\s+EXISTS\\s+"), "").trim()
}
