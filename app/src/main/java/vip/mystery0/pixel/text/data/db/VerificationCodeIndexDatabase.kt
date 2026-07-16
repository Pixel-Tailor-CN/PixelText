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
import androidx.room.Transaction
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.VerificationCodeIndexModel
import vip.mystery0.pixel.text.domain.model.VerificationCodeMonthModel

@Entity(
    tableName = "verification_code_index",
    primaryKeys = ["generation", "message_id"],
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

@Dao
interface VerificationCodeIndexDao {
    @Query(
        """
        SELECT month_key AS monthKey, MAX(timestamp) AS latestTimestamp, COUNT(*) AS messageCount
        FROM verification_code_index
        WHERE generation = COALESCE(
            (SELECT active_generation FROM verification_code_metadata WHERE id = 1),
            0
        )
        GROUP BY month_key
        ORDER BY month_key DESC
        """
    )
    fun observeMonths(): Flow<List<VerificationCodeMonthModel>>

    @Query(
        """
        SELECT message_id AS messageId, thread_id AS threadId, address, timestamp,
               month_key AS monthKey, code, signature, rule_version AS ruleVersion
        FROM verification_code_index
        WHERE generation = COALESCE(
            (SELECT active_generation FROM verification_code_metadata WHERE id = 1),
            0
        ) AND month_key = :monthKey
        ORDER BY timestamp DESC, message_id DESC
        """
    )
    fun observeMonth(monthKey: String): Flow<List<VerificationCodeIndexModel>>

    @Query("SELECT * FROM verification_code_metadata WHERE id = 1")
    suspend fun getMetadata(): VerificationCodeMetadataEntity?

    @Query(
        """
        SELECT * FROM verification_code_index
        WHERE generation = COALESCE(
            (SELECT active_generation FROM verification_code_metadata WHERE id = 1),
            0
        )
        """
    )
    suspend fun getActiveEntries(): List<VerificationCodeIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<VerificationCodeIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: VerificationCodeMetadataEntity)

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
}

@Database(
    entities = [
        VerificationCodeIndexEntity::class,
        VerificationCodeMetadataEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class VerificationCodeIndexDatabase : RoomDatabase() {
    abstract fun verificationCodeIndexDao(): VerificationCodeIndexDao

    suspend fun activateGeneration(
        generation: Long,
        ruleVersion: String,
        reconciledAt: Long,
    ) {
        withTransaction {
            verificationCodeIndexDao().upsertMetadata(
                VerificationCodeMetadataEntity(
                    activeGeneration = generation,
                    lastFullScanRuleVersion = ruleVersion,
                    lastReconciledAt = reconciledAt,
                )
            )
            verificationCodeIndexDao().deleteOtherGenerations(generation)
        }
    }

    companion object {
        fun create(context: Context): VerificationCodeIndexDatabase =
            Room.databaseBuilder(
                context,
                VerificationCodeIndexDatabase::class.java,
                "verification_code_index.db",
            ).build()
    }
}
