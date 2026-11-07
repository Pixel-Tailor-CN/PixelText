package vip.mystery0.pixel.text.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.ConversationModel

/** 只保存用户的归档选择，摘要由当前短信关联生成。 */
@Entity(tableName = "archived_conversation")
data class ArchivedConversationEntity(
    @PrimaryKey @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long,
)

@Dao
interface ArchivedConversationDao {
    @Query("SELECT thread_id FROM archived_conversation")
    suspend fun getArchivedThreadIds(): List<Long>

    @Query("SELECT thread_id FROM archived_conversation")
    fun observeArchivedThreadIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun archive(conversations: List<ArchivedConversationEntity>)

    @Query("DELETE FROM archived_conversation WHERE thread_id IN (:threadIds)")
    suspend fun unarchive(threadIds: Set<Long>)
}

fun ConversationModel.toArchivedConversationEntity(archivedAt: Long) =
    ArchivedConversationEntity(threadId = threadId, archivedAt = archivedAt)
