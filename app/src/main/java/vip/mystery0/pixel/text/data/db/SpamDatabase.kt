package vip.mystery0.pixel.text.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "spam_result")
data class SpamResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "spam_score") val spamScore: Float,
    @ColumnInfo(name = "checked_at") val checkedAt: Long
)

@Dao
interface SpamResultDao {
    @Query("SELECT spam_score FROM spam_result WHERE message_id = :messageId")
    suspend fun getScore(messageId: Long): Float?

    @Query("SELECT message_id FROM spam_result WHERE message_id IN (:messageIds)")
    suspend fun getExistingMessageIds(messageIds: List<Long>): List<Long>

    @Query(
        """
        SELECT thread_id
        FROM spam_result
        WHERE spam_score >= :threshold
        GROUP BY thread_id
        ORDER BY MAX(checked_at) DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: SpamResultEntity)
}

@Database(
    entities = [SpamResultEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SpamDatabase : RoomDatabase() {
    abstract fun spamResultDao(): SpamResultDao

    companion object {
        fun create(context: Context): SpamDatabase {
            return Room.databaseBuilder(
                context,
                SpamDatabase::class.java,
                "spam.db"
            )
                .build()
        }
    }
}
