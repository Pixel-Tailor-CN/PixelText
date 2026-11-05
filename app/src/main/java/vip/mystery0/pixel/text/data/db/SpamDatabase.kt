package vip.mystery0.pixel.text.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "blocked_keyword",
    indices = [Index(value = ["normalized_keyword"], unique = true)],
)
data class BlockedKeywordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keyword: String,
    @ColumnInfo(name = "normalized_keyword") val normalizedKeyword: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface BlockedKeywordDao {
    @Query("SELECT * FROM blocked_keyword ORDER BY updated_at DESC, id DESC")
    fun observeAll(): Flow<List<BlockedKeywordEntity>>

    @Query("SELECT * FROM blocked_keyword ORDER BY updated_at DESC, id DESC")
    suspend fun getAll(): List<BlockedKeywordEntity>

    @Query("SELECT * FROM blocked_keyword WHERE id = :id")
    suspend fun getById(id: Long): BlockedKeywordEntity?

    @Query("SELECT * FROM blocked_keyword WHERE normalized_keyword = :normalizedKeyword")
    suspend fun getByNormalizedKeyword(normalizedKeyword: String): BlockedKeywordEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BlockedKeywordEntity): Long

    @Update
    suspend fun update(entity: BlockedKeywordEntity)

    @Query("DELETE FROM blocked_keyword WHERE id = :id")
    suspend fun deleteById(id: Long)

}

@Database(
    entities = [
        BlockedKeywordEntity::class,
        SenderWhitelistRuleEntity::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class SpamDatabase : RoomDatabase() {
    abstract fun blockedKeywordDao(): BlockedKeywordDao
    abstract fun senderWhitelistDao(): SenderWhitelistDao

    companion object {
        fun create(context: Context): SpamDatabase {
            return Room.databaseBuilder(
                context,
                SpamDatabase::class.java,
                "spam.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }

        // 旧逐消息表暂留作导入来源；镜像事务提交完成标记后，由导入器删除。
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE spam_result ADD COLUMN source TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE spam_result ADD COLUMN model_identity TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE spam_result ADD COLUMN personal_generation INTEGER")
                db.execSQL("ALTER TABLE spam_result ADD COLUMN message_fingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE spam_result ADD COLUMN decision_revision INTEGER NOT NULL DEFAULT -1")
                // 旧分数没有来源，不能当作人工标注或新版模型预测。
                db.execSQL("DELETE FROM spam_result")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sender_whitelist_rule (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sender_whitelist_rule_type_value ON sender_whitelist_rule(type, value)")
                db.execSQL("CREATE TABLE IF NOT EXISTS spam_allowed_message (message_id INTEGER NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, allowed_at INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS blocked_keyword (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        keyword TEXT NOT NULL,
                        normalized_keyword TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_blocked_keyword_normalized_keyword " +
                        "ON blocked_keyword(normalized_keyword)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS keyword_spam_match (
                        message_id INTEGER NOT NULL,
                        thread_id INTEGER NOT NULL,
                        keyword_id INTEGER NOT NULL,
                        matched_at INTEGER NOT NULL,
                        PRIMARY KEY(message_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_keyword_spam_match_thread_id " +
                        "ON keyword_spam_match(thread_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_keyword_spam_match_keyword_id " +
                        "ON keyword_spam_match(keyword_id)"
                )
            }
        }
    }
}
