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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
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
    @Suppress(RoomWarnings.QUERY_MISMATCH)
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

    @Query(
        """
        SELECT message_id FROM verification_code_index
        WHERE generation = COALESCE(
            (SELECT active_generation FROM verification_code_metadata WHERE id = 1),
            0
        ) AND timestamp < :cutoffTimestamp
        ORDER BY timestamp ASC, message_id ASC
        """
    )
    suspend fun getExpiredMessageIds(cutoffTimestamp: Long): List<Long>

    @Query("SELECT * FROM sms_scan_state WHERE generation = :generation AND message_id IN (:messageIds)")
    suspend fun getScanStates(generation: Long, messageIds: List<Long>): List<SmsScanStateEntity>

    @Query("SELECT * FROM verification_code_index WHERE generation = :generation AND message_id IN (:messageIds)")
    suspend fun getEntries(generation: Long, messageIds: List<Long>): List<VerificationCodeIndexEntity>

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

@Database(
    entities = [
        VerificationCodeIndexEntity::class,
        VerificationCodeMetadataEntity::class,
        SmsScanStateEntity::class,
    ],
    version = 3,
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
            verificationCodeIndexDao().deleteOtherScanGenerations(generation)
        }
    }

    companion object {
        fun create(context: Context): VerificationCodeIndexDatabase =
            Room.databaseBuilder(
                context,
                VerificationCodeIndexDatabase::class.java,
                "verification_code_index.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sms_scan_state` (`generation` INTEGER NOT NULL, `message_id` INTEGER NOT NULL, `thread_id` INTEGER NOT NULL, `address` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `body_fingerprint` TEXT NOT NULL, PRIMARY KEY(`generation`, `message_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_scan_state_generation_message_id` ON `sms_scan_state` (`generation`, `message_id`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sms_scan_state` RENAME TO `sms_scan_state_v2`")
                db.execSQL("DROP INDEX IF EXISTS `index_sms_scan_state_generation_message_id`")
                db.execSQL("CREATE TABLE `sms_scan_state` (`generation` INTEGER NOT NULL, `message_id` INTEGER NOT NULL, `thread_id` INTEGER NOT NULL, `address` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `metadata_fingerprint` TEXT NOT NULL, PRIMARY KEY(`generation`, `message_id`))")
                db.execSQL("CREATE INDEX `index_sms_scan_state_generation_message_id` ON `sms_scan_state` (`generation`, `message_id`)")
                db.execSQL("DROP TABLE `sms_scan_state_v2`")
            }
        }
    }
}
