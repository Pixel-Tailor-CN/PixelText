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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import vip.mystery0.pixel.text.domain.model.ConversationModel

@Entity(tableName = "cached_conversation")
data class CachedConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "thread_id") val threadId: Long,
    val address: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    val snippet: String,
    val timestamp: Long,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "is_mms") val isMms: Int,
    @ColumnInfo(name = "has_mms") val hasMms: Int,
)

@Entity(tableName = "conversation_cache_metadata")
data class CacheMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "metadata_key") val key: String,
    val value: Int,
)

@Dao
interface CachedConversationDao {
    @Query("SELECT * FROM cached_conversation ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getConversations(limit: Int, offset: Int): List<CachedConversationEntity>

    @Query("SELECT * FROM cached_conversation ORDER BY timestamp DESC")
    suspend fun getAllConversations(): List<CachedConversationEntity>

    @Query("SELECT thread_id FROM cached_conversation")
    suspend fun getAllThreadIds(): List<Long>

    @Query("SELECT COUNT(*) FROM cached_conversation")
    suspend fun count(): Int

    @Query("SELECT value FROM conversation_cache_metadata WHERE metadata_key = :key")
    suspend fun getMetadataValue(key: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversations: List<CachedConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: CacheMetadataEntity)

    @Query("DELETE FROM cached_conversation WHERE thread_id IN (:threadIds)")
    suspend fun delete(threadIds: Set<Long>)

    @Query("DELETE FROM cached_conversation")
    suspend fun deleteAll()
}

@Database(
    entities = [
        CachedConversationEntity::class,
        CacheMetadataEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ConversationCacheDatabase : RoomDatabase() {
    abstract fun cachedConversationDao(): CachedConversationDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_cache_metadata` (
                        `metadata_key` TEXT NOT NULL,
                        `value` INTEGER NOT NULL,
                        PRIMARY KEY(`metadata_key`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context): ConversationCacheDatabase {
            return Room.databaseBuilder(
                context,
                ConversationCacheDatabase::class.java,
                "conversation_cache.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}

fun CachedConversationEntity.toConversationModel() = ConversationModel(
    threadId = threadId,
    address = address,
    displayName = displayName,
    snippet = snippet,
    timestamp = timestamp,
    unreadCount = unreadCount,
    isMms = isMms == 1,
    hasMms = hasMms == 1,
)

fun ConversationModel.toCachedConversationEntity() = CachedConversationEntity(
    threadId = threadId,
    address = address,
    displayName = displayName,
    snippet = snippet,
    timestamp = timestamp,
    unreadCount = unreadCount,
    isMms = if (isMms) 1 else 0,
    hasMms = if (hasMms) 1 else 0,
)
