package vip.mystery0.pixel.text.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomWarnings
import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.VerificationCodeIndexModel
import vip.mystery0.pixel.text.domain.model.VerificationCodeMonthModel

@Entity(
    tableName = "verification_code_index",
    primaryKeys = ["generation", "message_id"],
    indices = [
        Index(value = ["generation", "month_key", "timestamp"]),
        Index(value = ["thread_id"]),
    ],
)
data class VerificationCodeIndexEntity(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    val address: String,
    val timestamp: Long,
    @ColumnInfo(name = "month_key") val monthKey: String,
    val code: String,
    val signature: String?,
    @ColumnInfo(name = "rule_version") val ruleVersion: String,
    val generation: Long,
)

@Entity(tableName = "verification_code_metadata")
data class VerificationCodeMetadataEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "active_generation") val activeGeneration: Long,
    @ColumnInfo(name = "last_full_scan_rule_version") val lastFullScanRuleVersion: String?,
    @ColumnInfo(name = "last_reconciled_at") val lastReconciledAt: Long?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "sms_scan_state",
    primaryKeys = ["generation", "message_id"],
    indices = [Index(value = ["generation", "message_id"])],
)
data class SmsScanStateEntity(
    val generation: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    val address: String,
    val timestamp: Long,
    @ColumnInfo(name = "metadata_fingerprint") val metadataFingerprint: String,
)

@Dao
interface VerificationCodeIndexDao {
    // 只展示已同步的原消息；时间和地址校验避免 Provider 复用 ID 时关联旧验证码。
    // 镜像暂缺只影响可见结果，不据此删除索引，后续同步由 Room 自动刷新。
    @Query(
        """
        SELECT entry.month_key AS monthKey, MAX(entry.timestamp) AS latestTimestamp,
               COUNT(*) AS messageCount
        FROM verification_code_index AS entry
        INNER JOIN mirror_message AS message
            ON message.transport = 'SMS' AND message.sourceId = entry.message_id
            AND message.timestamp = entry.timestamp
        INNER JOIN mirror_sms AS sms ON sms.localId = message.localId
            AND COALESCE(sms.address, '') = entry.address
        WHERE entry.generation = COALESCE(
            (SELECT active_generation FROM verification_code_metadata WHERE id = 1),
            0
        )
        GROUP BY entry.month_key
        ORDER BY entry.month_key DESC
        """
    )
    fun observeMonths(): Flow<List<VerificationCodeMonthModel>>

    @Query(
        """
        SELECT entry.message_id AS messageId, COALESCE(message.threadId, entry.thread_id) AS threadId,
               entry.address, entry.timestamp, entry.month_key AS monthKey,
               entry.code, entry.signature, entry.rule_version AS ruleVersion
        FROM verification_code_index AS entry
        INNER JOIN mirror_message AS message
            ON message.transport = 'SMS' AND message.sourceId = entry.message_id
            AND message.timestamp = entry.timestamp
        INNER JOIN mirror_sms AS sms ON sms.localId = message.localId
            AND COALESCE(sms.address, '') = entry.address
        WHERE entry.generation = COALESCE(
            (SELECT active_generation FROM verification_code_metadata WHERE id = 1),
            0
        ) AND entry.month_key = :monthKey
        ORDER BY entry.timestamp DESC, entry.message_id DESC
        """
    )
    @Suppress(RoomWarnings.QUERY_MISMATCH)
    fun observeMonth(monthKey: String): Flow<List<VerificationCodeIndexModel>>

    @Query("SELECT * FROM verification_code_metadata WHERE id = 1")
    suspend fun getMetadata(): VerificationCodeMetadataEntity?

    @Query("""
        SELECT sms.body FROM mirror_message AS message
        INNER JOIN mirror_sms AS sms ON sms.localId = message.localId
        WHERE message.transport = 'SMS' AND message.sourceId = :messageId
    """)
    suspend fun getMessageBody(messageId: Long): String?

    @Query(
        """
        SELECT entry.message_id FROM verification_code_index AS entry
        INNER JOIN mirror_message AS message
            ON message.transport = 'SMS' AND message.sourceId = entry.message_id
            AND message.timestamp = entry.timestamp
        INNER JOIN mirror_sms AS sms ON sms.localId = message.localId
            AND COALESCE(sms.address, '') = entry.address
        WHERE entry.generation = COALESCE(
            (SELECT active_generation FROM verification_code_metadata WHERE id = 1),
            0
        ) AND entry.timestamp < :cutoffTimestamp
        ORDER BY entry.timestamp ASC, entry.message_id ASC
        """
    )
    suspend fun getExpiredMessageIds(cutoffTimestamp: Long): List<Long>

    @Query("SELECT * FROM sms_scan_state WHERE generation = :generation AND message_id IN (:messageIds)")
    suspend fun getScanStates(generation: Long, messageIds: List<Long>): List<SmsScanStateEntity>

    @Query("SELECT COUNT(*) FROM sms_scan_state WHERE generation = :generation")
    suspend fun getScanStateCount(generation: Long): Long

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM verification_code_index AS entry
            LEFT JOIN sms_scan_state AS state
                ON state.generation = entry.generation AND state.message_id = entry.message_id
            WHERE entry.generation = :generation AND state.message_id IS NULL
        )
        """
    )
    suspend fun hasEntriesWithoutScanState(generation: Long): Boolean

    /** 未变化的识别结果直接在库内复制到新代，避免读取并重新组装整批对象。 */
    @Query("""
        INSERT INTO verification_code_index
            (message_id, thread_id, address, timestamp, month_key, code, signature, rule_version, generation)
        SELECT message_id, thread_id, address, timestamp, month_key, code, signature, rule_version, :generation
        FROM verification_code_index
        WHERE generation = :sourceGeneration AND message_id IN (:messageIds)
    """)
    suspend fun copyEntries(sourceGeneration: Long, generation: Long, messageIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<VerificationCodeIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScanStates(entries: List<SmsScanStateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: VerificationCodeMetadataEntity)

    @Query("UPDATE verification_code_metadata SET last_reconciled_at = :timestamp WHERE id = 1")
    suspend fun updateLastReconciledAt(timestamp: Long)

    @Query("DELETE FROM verification_code_index WHERE generation = :generation AND message_id = :messageId")
    suspend fun deleteMessage(generation: Long, messageId: Long)

    @Query("DELETE FROM verification_code_index WHERE message_id IN (:messageIds)")
    suspend fun deleteMessageIds(messageIds: List<Long>)

    @Query("DELETE FROM verification_code_index WHERE thread_id IN (:threadIds)")
    suspend fun deleteThreadIds(threadIds: List<Long>)

    @Query("DELETE FROM verification_code_index WHERE generation = :generation")
    suspend fun deleteGeneration(generation: Long)

    @Query("DELETE FROM verification_code_index WHERE generation != :generation")
    suspend fun deleteOtherGenerations(generation: Long)

    @Query("DELETE FROM sms_scan_state WHERE generation = :generation")
    suspend fun deleteScanGeneration(generation: Long)

    @Query("DELETE FROM sms_scan_state WHERE generation != :generation")
    suspend fun deleteOtherScanGenerations(generation: Long)
}
