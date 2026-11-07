package vip.mystery0.pixel.text.data.db.mirror

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 旧业务库只在主库打开时读取；完成标记与导入内容一起提交，之后才清理旧文件。 */
internal class MirrorAuxiliaryDatabaseMigration(private val context: Context) : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS blocked_keyword (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, keyword TEXT NOT NULL, normalized_keyword TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_blocked_keyword_normalized_keyword ON blocked_keyword(normalized_keyword)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sender_whitelist_rule (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sender_whitelist_rule_type_value ON sender_whitelist_rule(type, value)")
        db.execSQL("CREATE TABLE IF NOT EXISTS verification_code_index (message_id INTEGER NOT NULL, thread_id INTEGER NOT NULL, address TEXT NOT NULL, timestamp INTEGER NOT NULL, month_key TEXT NOT NULL, code TEXT NOT NULL, signature TEXT, rule_version TEXT NOT NULL, generation INTEGER NOT NULL, PRIMARY KEY(generation, message_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_verification_code_index_generation_month_key_timestamp ON verification_code_index(generation, month_key, timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_verification_code_index_thread_id ON verification_code_index(thread_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS verification_code_metadata (id INTEGER NOT NULL PRIMARY KEY, active_generation INTEGER NOT NULL, last_full_scan_rule_version TEXT, last_reconciled_at INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sms_scan_state (generation INTEGER NOT NULL, message_id INTEGER NOT NULL, thread_id INTEGER NOT NULL, address TEXT NOT NULL, timestamp INTEGER NOT NULL, metadata_fingerprint TEXT NOT NULL, PRIMARY KEY(generation, message_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sms_scan_state_generation_message_id ON sms_scan_state(generation, message_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS archived_conversation (thread_id INTEGER NOT NULL PRIMARY KEY, archived_at INTEGER NOT NULL)")
        importLegacy(db)
    }

    fun importLegacy(db: SupportSQLiteDatabase) {
        if (isImported(db)) return
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            readLegacy("spam.db") { old ->
                copyTable(old, db, "blocked_keyword", "id, keyword, normalized_keyword, created_at, updated_at")
                copyTable(old, db, "sender_whitelist_rule", "id, type, value, updated_at")
            }
            readLegacy("verification_code_index.db") { old ->
                copyTable(old, db, "verification_code_index", "message_id, thread_id, address, timestamp, month_key, code, signature, rule_version, generation")
                copyTable(old, db, "verification_code_metadata", "id, active_generation, last_full_scan_rule_version, last_reconciled_at")
                // v2 使用正文指纹，与新版元数据指纹不兼容；保留验证码，扫描状态交由后续重建。
                if (old.hasColumn("sms_scan_state", "metadata_fingerprint")) {
                    copyTable(old, db, "sms_scan_state", "generation, message_id, thread_id, address, timestamp, metadata_fingerprint")
                }
            }
            readLegacy("conversation_archive.db") { old ->
                copyTable(old, db, "archived_conversation", "thread_id, archived_at")
            }
            db.execSQL("""
                INSERT INTO mirror_sync_state
                    (collection, generation, checkpoint, complete, running, lastSuccessTime, error, columns, processedCount, completedSources, dirtyToken)
                VALUES (?, 0, NULL, 1, 0, ?, NULL, '[]', 0, '[]', NULL)
            """.trimIndent(), arrayOf<Any>(MARKER, System.currentTimeMillis()))
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    fun cleanImportedFiles(db: SupportSQLiteDatabase) {
        if (db.inTransaction() || !isImported(db)) return
        // spam.db 中还可能有逐短信判定，交给 MirrorSpamLegacyImporter 成功后再删除。
        listOf("verification_code_index.db", "conversation_archive.db").forEach { name ->
            if (context.getDatabasePath(name).exists()) context.deleteDatabase(name)
        }
    }

    private fun isImported(db: SupportSQLiteDatabase): Boolean = db.query(
        "SELECT 1 FROM mirror_sync_state WHERE collection=? AND complete=1", arrayOf(MARKER),
    ).use { it.moveToFirst() }

    private inline fun readLegacy(name: String, read: (SQLiteDatabase) -> Unit) {
        val file = context.getDatabasePath(name)
        if (!file.exists()) return
        // 默认损坏处理器即使只读打开也会删除源文件，迁移失败时必须保留原件。
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) {
            throw SQLiteDatabaseCorruptException("legacy database corrupted")
        }.use(read)
    }

    private fun copyTable(old: SQLiteDatabase, target: SupportSQLiteDatabase, table: String, columns: String) {
        if (!old.hasTable(table)) return
        old.rawQuery("SELECT $columns FROM $table", null).use { cursor ->
            val placeholders = List(cursor.columnCount) { "?" }.joinToString(",")
            target.compileStatement("INSERT INTO $table ($columns) VALUES ($placeholders)").use { statement ->
                while (cursor.moveToNext()) {
                    for (index in 0 until cursor.columnCount) {
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> statement.bindNull(index + 1)
                            Cursor.FIELD_TYPE_INTEGER -> statement.bindLong(index + 1, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> statement.bindDouble(index + 1, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_BLOB -> statement.bindBlob(index + 1, cursor.getBlob(index))
                            else -> statement.bindString(index + 1, cursor.getString(index))
                        }
                    }
                    statement.executeInsert()
                }
            }
        }
    }

    private fun SQLiteDatabase.hasTable(table: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table),
    ).use { it.moveToFirst() }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }

    private companion object {
        const val MARKER = "auxiliary_database_import"
    }
}
