package vip.mystery0.pixel.text.data.backup

import android.database.sqlite.SQLiteDatabase
import vip.mystery0.pixel.text.data.db.BlockedKeywordEntity
import vip.mystery0.pixel.text.data.db.SenderWhitelistRuleEntity
import vip.mystery0.pixel.text.data.repository.SenderWhitelistRepositoryImpl
import java.io.File

class BackupRuleStore(private val whitelist: SenderWhitelistRepositoryImpl) {
    suspend fun restore(directory: File) {
        val file = File(directory, "databases/spam.db")
        val keywords = mutableListOf<BlockedKeywordEntity>()
        val rules = mutableListOf<SenderWhitelistRuleEntity>()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT * FROM blocked_keyword", null).use { c ->
                while (c.moveToNext()) keywords += BlockedKeywordEntity(
                    keyword = c.stringOrNull("keyword")!!, normalizedKeyword = c.stringOrNull("normalized_keyword")!!,
                    createdAt = c.longOrNull("created_at")!!, updatedAt = c.longOrNull("updated_at")!!,
                )
            }
            db.rawQuery("SELECT * FROM sender_whitelist_rule", null).use { c ->
                while (c.moveToNext()) rules += SenderWhitelistRuleEntity(
                    type = c.stringOrNull("type")!!, value = c.stringOrNull("value")!!, updatedAt = c.longOrNull("updated_at")!!,
                )
            }
        }
        whitelist.mergeBackupRules(keywords, rules)
    }
}
