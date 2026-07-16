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
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "spam_result")
data class SpamResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "spam_score") val spamScore: Float,
    @ColumnInfo(name = "checked_at") val checkedAt: Long
)

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

@Entity(
    tableName = "keyword_spam_match",
    indices = [Index("thread_id"), Index("keyword_id")],
)
data class KeywordSpamMatchEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "keyword_id") val keywordId: Long,
    @ColumnInfo(name = "matched_at") val matchedAt: Long,
)

@Dao
interface SpamResultDao {
    @Query(
        """
        SELECT CASE
            WHEN EXISTS(
                SELECT 1 FROM keyword_spam_match WHERE message_id = :messageId
            ) THEN 1.0
            ELSE (
                SELECT spam_score FROM spam_result WHERE message_id = :messageId
            )
        END
        """
    )
    suspend fun getScore(messageId: Long): Float?

    @Query("SELECT message_id FROM spam_result WHERE message_id IN (:messageIds)")
    suspend fun getExistingMessageIds(messageIds: List<Long>): List<Long>

    @Query(
        """
        SELECT message_id FROM spam_result
        WHERE message_id IN (:messageIds) AND spam_score >= :threshold
        UNION
        SELECT message_id FROM keyword_spam_match
        WHERE message_id IN (:messageIds)
        """
    )
    suspend fun getSpamMessageIds(messageIds: List<Long>, threshold: Float): List<Long>

    @Query(
        """
        SELECT thread_id FROM (
            SELECT thread_id, checked_at AS matched_at
            FROM spam_result
            WHERE spam_score >= :threshold
            UNION ALL
            SELECT thread_id, matched_at
            FROM keyword_spam_match
        )
        GROUP BY thread_id
        ORDER BY MAX(matched_at) DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM spam_result) +
            (SELECT COUNT(*) FROM keyword_spam_match)
        """
    )
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM spam_result WHERE message_id IN (:messageIds)")
    suspend fun deleteByMessageIds(messageIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: SpamResultEntity)
}

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(entity: KeywordSpamMatchEntity)

    @Query("DELETE FROM keyword_spam_match WHERE message_id = :messageId")
    suspend fun deleteMatch(messageId: Long)

    @Query("DELETE FROM keyword_spam_match WHERE message_id IN (:messageIds)")
    suspend fun deleteMatches(messageIds: List<Long>)

    @Query("DELETE FROM keyword_spam_match")
    suspend fun deleteAllMatches()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatches(matches: List<KeywordSpamMatchEntity>)

    @Transaction
    suspend fun replaceAllMatches(matches: List<KeywordSpamMatchEntity>) {
        deleteAllMatches()
        if (matches.isNotEmpty()) insertMatches(matches)
    }
}

@Database(
    entities = [
        SpamResultEntity::class,
        BlockedKeywordEntity::class,
        KeywordSpamMatchEntity::class,
    ],
    version = 2,
    exportSchema = false
)
abstract class SpamDatabase : RoomDatabase() {
    abstract fun spamResultDao(): SpamResultDao
    abstract fun blockedKeywordDao(): BlockedKeywordDao

    companion object {
        fun create(context: Context): SpamDatabase {
            return Room.databaseBuilder(
                context,
                SpamDatabase::class.java,
                "spam.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
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
