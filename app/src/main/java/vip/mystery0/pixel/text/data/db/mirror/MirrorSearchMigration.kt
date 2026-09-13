package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import vip.mystery0.pixel.text.domain.model.search.SearchPhoneNumbers

/** 只读取本地原始地址，升级不依赖 Provider 权限，不改写源字段或附件。 */
object MirrorSearchMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mirror_sms ADD COLUMN normalizedAddress TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE mms_text_index ADD COLUMN searchBody TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE mms_text_index ADD COLUMN searchReady INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE mms_text_index ADD COLUMN sourceRevision INTEGER NOT NULL DEFAULT 0")
        backfill(db, "mirror_sms")
        backfill(db, "mirror_mms_address")
    }

    private fun backfill(db: SupportSQLiteDatabase, table: String) {
        var afterId = -1L
        while (true) {
            // 两个表均为普通 rowid 表，MMS 的复合主键不会影响此迁移游标。
            val rows = db.query("SELECT rowid, address FROM $table WHERE rowid > ? ORDER BY rowid LIMIT 200",
                arrayOf(afterId)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
            }
            if (rows.isEmpty()) return
            rows.forEach { (id, address) ->
                db.execSQL("UPDATE $table SET normalizedAddress=? WHERE rowid=?",
                    arrayOf<Any>(SearchPhoneNumbers.digits(address), id))
            }
            afterId = rows.last().first
        }
    }
}
