package vip.mystery0.pixel.text.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.domain.backup.BackupSection
import java.io.File

/** 从 Room 的事务视图复制允许数据，不能复制主文件后 DELETE 裁剪。 */
class AppDatabaseSnapshotter(
    private val mirror: MessageMirrorDatabase,
    private val spam: SpamDatabase,
    private val archive: ConversationArchiveDatabase,
) {
    val definitions: List<SnapshotDefinition> get() = listOf(
        SnapshotDefinition("message_mirror.db", mirror, 3, listOf("mirror_message", "mirror_sms")),
        SnapshotDefinition("spam.db", spam, 3, listOf("blocked_keyword", "sender_whitelist_rule", "spam_allowed_message")),
        SnapshotDefinition("conversation_archive.db", archive, 1, listOf("archived_conversation")),
    )

    suspend fun capture(directory: File, sections: Set<BackupSection>) {
        val databases = File(directory, "databases").also { it.mkdirs() }
        val hasSms = BackupSection.SMS in sections
        for (definition in definitions) {
            if (definition.name != "spam.db" && !hasSms) continue
            if (definition.name == "spam.db" && !hasSms && BackupSection.RULES !in sections) continue
            val source = definition.database.openHelper.readableDatabase
            val file = File(databases, definition.name)
            val smsSnapshot = if (hasSms && definition.name != "message_mirror.db")
                SQLiteDatabase.openDatabase(File(databases, "message_mirror.db").path, null, SQLiteDatabase.OPEN_READONLY) else null
            try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { target ->
                target.rawQuery("PRAGMA journal_mode=DELETE", null).use { backupRequire(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true)) }
                target.version = definition.version
                target.beginTransaction()
                try {
                    source.beginTransaction()
                    try {
                        for (table in definition.tables) {
                            target.execSQL(tableSql(source, table))
                            val sql = when (table) {
                                "mirror_message" -> "SELECT * FROM mirror_message WHERE transport='SMS' ORDER BY localId"
                                "mirror_sms" -> "SELECT s.* FROM mirror_sms s JOIN mirror_message m ON s.localId=m.localId WHERE m.transport='SMS' ORDER BY s.localId"
                                "blocked_keyword", "sender_whitelist_rule" -> "SELECT * FROM $table" + if (BackupSection.RULES in sections) "" else " WHERE 0"
                                "spam_allowed_message" -> "SELECT * FROM spam_allowed_message WHERE message_id>0" + if (hasSms) "" else " AND 0"
                                else -> "SELECT * FROM $table"
                            }
                            source.query(sql).use { cursor ->
                                var count = 0L
                                while (cursor.moveToNext()) {
                                    currentCoroutineContext().ensureActive()
                                    backupRequire(++count <= MAX_SMS_COUNT, "数据数量超过备份限制")
                                    val row = ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) }
                                    // 状态只允许关联本次快照中的 SMS。
                                    val accepted = when (table) {
                                        "spam_allowed_message" -> matchesSms(smsSnapshot, "sourceId", row.getAsLong("message_id"))
                                        "archived_conversation" -> matchesSms(smsSnapshot, "threadId", row.getAsLong("thread_id"))
                                        else -> true
                                    }
                                    if (table == "archived_conversation") {
                                        row.put("snippet", ""); row.putNull("display_name")
                                        row.put("unread_count", 0); row.put("is_mms", 0); row.put("has_mms", 0)
                                    }
                                    if (accepted) backupRequire(target.insertOrThrow(table, null, row) != -1L)
                                }
                            }
                        }
                        if (definition.name == "message_mirror.db") {
                            target.execSQL("CREATE UNIQUE INDEX backup_sms_source ON mirror_message(sourceId)")
                            target.execSQL("CREATE INDEX backup_sms_thread ON mirror_message(threadId)")
                        }
                        source.setTransactionSuccessful()
                    } finally { source.endTransaction() }
                    target.setTransactionSuccessful()
                } finally { target.endTransaction() }
            }
            } finally { smsSnapshot?.close() }
            backupRequire(file.length() <= MAX_BACKUP_BYTES, "数据库快照过大")
        }
    }

    private fun matchesSms(db: SQLiteDatabase?, column: String, value: Long?): Boolean {
        if (value == null || db == null) return false
        return db.rawQuery("SELECT 1 FROM mirror_message WHERE $column=? LIMIT 1", arrayOf(value.toString())).use { it.moveToFirst() }
    }
}

data class SnapshotDefinition(val name: String, val database: RoomDatabase, val version: Int, val tables: List<String>)
internal fun tableSql(database: SupportSQLiteDatabase, table: String): String =
    database.query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
        backupRequire(it.moveToFirst(), "无法读取应用数据库结构")
        it.getString(0)
    }
internal fun Cursor.stringOrNull(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
internal fun Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
