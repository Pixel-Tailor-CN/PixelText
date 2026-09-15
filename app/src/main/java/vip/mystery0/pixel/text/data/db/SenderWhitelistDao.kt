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

@Entity(tableName = "spam_allowed_message")
data class SpamAllowedMessageEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: Long,
    val fingerprint: String,
    @ColumnInfo(name = "allowed_at") val allowedAt: Long,
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

    @Query("SELECT * FROM spam_allowed_message WHERE message_id IN (:ids)")
    suspend fun getAllowed(ids: List<Long>): List<SpamAllowedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun allow(messages: List<SpamAllowedMessageEntity>)

    @Query("DELETE FROM spam_allowed_message WHERE message_id IN (:ids)")
    suspend fun forget(ids: List<Long>)

    @Query("DELETE FROM spam_allowed_message WHERE message_id = :id AND fingerprint = :fingerprint")
    suspend fun forgetIdentity(id: Long, fingerprint: String)
}
