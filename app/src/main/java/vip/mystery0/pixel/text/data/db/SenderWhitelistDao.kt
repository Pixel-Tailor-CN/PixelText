package vip.mystery0.pixel.text.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sender_whitelist_rule", indices = [Index(value = ["type", "value"], unique = true)])
data class SenderWhitelistRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val value: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface SenderWhitelistDao {
    @Query("SELECT * FROM sender_whitelist_rule ORDER BY updated_at DESC, id DESC")
    fun observeRules(): Flow<List<SenderWhitelistRuleEntity>>

    @Query("SELECT * FROM sender_whitelist_rule ORDER BY id")
    suspend fun getRules(): List<SenderWhitelistRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRules(rules: List<SenderWhitelistRuleEntity>)

    @Query("DELETE FROM sender_whitelist_rule WHERE id = :id")
    suspend fun deleteRule(id: Long)

}
