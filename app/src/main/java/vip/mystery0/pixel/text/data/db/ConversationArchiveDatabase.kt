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
import vip.mystery0.pixel.text.domain.model.ConversationModel

@Entity(tableName = "archived_conversation")
data class ArchivedConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "thread_id")
    val threadId: Long,
    val address: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    val snippet: String,
    val timestamp: Long,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "is_mms") val isMms: Int,
    @ColumnInfo(name = "has_mms") val hasMms: Int,
    @ColumnInfo(name = "archived_at") val archivedAt: Long
)

@Dao
interface ArchivedConversationDao {
    @Query("SELECT thread_id FROM archived_conversation")
    suspend fun getArchivedThreadIds(): List<Long>

    @Query("SELECT * FROM archived_conversation ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getArchivedConversations(limit: Int, offset: Int): List<ArchivedConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun archive(conversations: List<ArchivedConversationEntity>)

    @Query("DELETE FROM archived_conversation WHERE thread_id IN (:threadIds)")
    suspend fun unarchive(threadIds: Set<Long>)
}

@Database(
    entities = [ArchivedConversationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ConversationArchiveDatabase : RoomDatabase() {
    abstract fun archivedConversationDao(): ArchivedConversationDao

    companion object {
        fun create(context: Context): ConversationArchiveDatabase {
            return Room.databaseBuilder(
                context,
                ConversationArchiveDatabase::class.java,
                "conversation_archive.db"
            )
                .build()
        }
    }
}

fun ArchivedConversationEntity.toConversationModel(): ConversationModel {
    return ConversationModel(
        threadId = threadId,
        address = address,
        displayName = displayName,
        snippet = snippet,
        timestamp = timestamp,
        unreadCount = unreadCount,
        isMms = isMms == 1,
        hasMms = hasMms == 1
    )
}

fun ConversationModel.toArchivedConversationEntity(archivedAt: Long): ArchivedConversationEntity {
    return ArchivedConversationEntity(
        threadId = threadId,
        address = address,
        displayName = displayName,
        snippet = snippet,
        timestamp = timestamp,
        unreadCount = unreadCount,
        isMms = if (isMms) 1 else 0,
        hasMms = if (hasMms) 1 else 0,
        archivedAt = archivedAt
    )
}
