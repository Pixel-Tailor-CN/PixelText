package vip.mystery0.pixel.text.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
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
