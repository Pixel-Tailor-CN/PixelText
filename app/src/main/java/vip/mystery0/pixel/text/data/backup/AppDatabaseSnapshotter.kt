package vip.mystery0.pixel.text.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.source.whitelistMessageFingerprint
import vip.mystery0.pixel.text.domain.backup.BackupSection
import java.io.File

/** 从 Room 的事务视图复制允许数据，不能复制主文件后 DELETE 裁剪。 */
class AppDatabaseSnapshotter(
    private val mirror: MessageMirrorDatabase,
) {
    val definitions: List<SnapshotDefinition> get() = listOf(
        SnapshotDefinition("message_mirror.db", mirror, 3, listOf("mirror_message", "mirror_sms")),
        SnapshotDefinition("spam.db", mirror, 3, listOf("blocked_keyword", "sender_whitelist_rule", "spam_allowed_message")),
        SnapshotDefinition("conversation_archive.db", mirror, 1, listOf("archived_conversation")),
    )

    suspend fun capture(directory: File, sections: Set<BackupSection>) {
        val databases = File(directory, "databases").also { it.mkdirs() }
        val hasSms = BackupSection.SMS in sections
        val allowedMessages = mutableListOf<ContentValues>()
        for (definition in definitions) {
            if (definition.name != "spam.db" && !hasSms) continue
            if (definition.name == "spam.db" && !hasSms && BackupSection.RULES !in sections) continue
            val source = definition.database.openHelper.readableDatabase
            val file = File(databases, definition.name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { target ->
                target.rawQuery("PRAGMA journal_mode=DELETE", null).use { backupRequire(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true)) }
                target.version = definition.version
                target.beginTransaction()
                try {
                    source.beginTransaction()
                    try {
                        for (table in definition.tables) {
                            target.execSQL(snapshotTableSql(definition, table))
                            if (table == "spam_allowed_message") {
                                // 兼容备份协议中的旧表；运行库已将放行状态并入消息行。
                                allowedMessages.forEach { row ->
                                    currentCoroutineContext().ensureActive()
                                    backupRequire(target.insertOrThrow(table, null, row) != -1L)
                                }
                                continue
                            }
                            val sql = when (table) {
                                "mirror_message" -> "SELECT $BACKUP_MESSAGE_COLUMNS FROM mirror_message WHERE transport='SMS' ORDER BY localId"
                                "mirror_sms" -> "SELECT s.* FROM mirror_sms s JOIN mirror_message m ON s.localId=m.localId WHERE m.transport='SMS' ORDER BY s.localId"
                                "blocked_keyword", "sender_whitelist_rule" -> "SELECT * FROM $table" + if (BackupSection.RULES in sections) "" else " WHERE 0"
                                // SMS 备份持有镜像同步锁，关联条件与本次导出的短信集合保持一致。
                                "archived_conversation" -> "SELECT a.* FROM archived_conversation a WHERE EXISTS (SELECT 1 FROM mirror_message m WHERE m.threadId=a.thread_id AND m.transport='SMS')"
                                else -> "SELECT * FROM $table"
                            }
                            source.query(sql).use { cursor ->
                                var count = 0L
                                while (cursor.moveToNext()) {
                                    currentCoroutineContext().ensureActive()
                                    backupRequire(++count <= MAX_SMS_COUNT, "数据数量超过备份限制")
                                    val row = ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) }
                                    if (table == "archived_conversation") {
                                        // 旧协议包含展示字段，运行库只保留归档状态；恢复时不使用这些占位值。
                                        row.put("address", ""); row.put("timestamp", 0L)
                                        row.put("snippet", ""); row.putNull("display_name")
                                        row.put("unread_count", 0); row.put("is_mms", 0); row.put("has_mms", 0)
                                    }
                                    backupRequire(target.insertOrThrow(table, null, row) != -1L)
                                }
                            }
                        }
                        if (definition.name == "message_mirror.db") {
                            // 与短信快照共用同一读事务，避免放行状态关联到另一轮消息身份。
                            source.query("""
                                SELECT m.sourceId, m.originalDate, s.address, s.dateSent
                                FROM mirror_message m JOIN mirror_sms s ON s.localId=m.localId
                                WHERE m.transport='SMS' AND m.sourceId>0 AND m.spamAllowedSource IN (2,3)
                                ORDER BY m.localId
                            """.trimIndent()).use { cursor ->
                                while (cursor.moveToNext()) {
                                    currentCoroutineContext().ensureActive()
                                    backupRequire(allowedMessages.size.toLong() < MAX_SMS_COUNT)
                                    val id = cursor.longOrNull("sourceId")!!
                                    allowedMessages += ContentValues().apply {
                                        put("message_id", id)
                                        put("fingerprint", whitelistMessageFingerprint(id,
                                            cursor.stringOrNull("address").orEmpty(),
                                            cursor.longOrNull("originalDate") ?: 0,
                                            cursor.longOrNull("dateSent") ?: 0))
                                        // 新状态不保存操作时间，旧协议该字段仅作记录，不参与恢复判定。
                                        put("allowed_at", System.currentTimeMillis())
                                    }
                                }
                            }
                            target.execSQL("CREATE UNIQUE INDEX backup_sms_source ON mirror_message(sourceId)")
                            target.execSQL("CREATE INDEX backup_sms_thread ON mirror_message(threadId)")
                        }
                        source.setTransactionSuccessful()
                    } finally { source.endTransaction() }
                    target.setTransactionSuccessful()
                } finally { target.endTransaction() }
            }
            backupRequire(file.length() <= MAX_BACKUP_BYTES, "数据库快照过大")
        }
    }
}

data class SnapshotDefinition(val name: String, val database: RoomDatabase, val version: Int, val tables: List<String>)

// 备份 v1 的传输结构固定，不随运行库新增分类字段而变化，也不导出模型评分。
private const val BACKUP_MESSAGE_COLUMNS = "localId, transport, sourceId, revision, threadId, timestamp, originalDate, dateUnit, subscriptionId, boxType, read, seen, structureComplete, generation"
private const val BACKUP_MESSAGE_TABLE = "CREATE TABLE IF NOT EXISTS `mirror_message` (`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transport` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `threadId` INTEGER, `timestamp` INTEGER, `originalDate` INTEGER, `dateUnit` TEXT NOT NULL, `subscriptionId` INTEGER, `boxType` INTEGER, `read` INTEGER, `seen` INTEGER, `structureComplete` INTEGER NOT NULL, `generation` INTEGER NOT NULL)"
private const val BACKUP_ALLOWED_TABLE = "CREATE TABLE IF NOT EXISTS `spam_allowed_message` (`message_id` INTEGER NOT NULL, `fingerprint` TEXT NOT NULL, `allowed_at` INTEGER NOT NULL, PRIMARY KEY(`message_id`))"
private const val BACKUP_ARCHIVED_TABLE = "CREATE TABLE IF NOT EXISTS `archived_conversation` (`thread_id` INTEGER NOT NULL, `address` TEXT NOT NULL, `display_name` TEXT, `snippet` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, `is_mms` INTEGER NOT NULL, `has_mms` INTEGER NOT NULL, `archived_at` INTEGER NOT NULL, PRIMARY KEY(`thread_id`))"

internal fun snapshotTableSql(definition: SnapshotDefinition, table: String): String = when {
    definition.name == "message_mirror.db" && table == "mirror_message" -> BACKUP_MESSAGE_TABLE
    definition.name == "spam.db" && table == "spam_allowed_message" -> BACKUP_ALLOWED_TABLE
    definition.name == "conversation_archive.db" && table == "archived_conversation" -> BACKUP_ARCHIVED_TABLE
    else -> tableSql(definition.database.openHelper.readableDatabase, table)
}

internal fun tableSql(database: SupportSQLiteDatabase, table: String): String =
    database.query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
        backupRequire(it.moveToFirst(), "无法读取应用数据库结构")
        it.getString(0)
    }
internal fun Cursor.stringOrNull(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
internal fun Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
