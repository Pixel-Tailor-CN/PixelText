package vip.mystery0.pixel.text.data.db.outgoing

import android.content.Context
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
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "draft")
data class MmsDraftEntity(
    @PrimaryKey val id: String,
    val revision: Long,
    val recipientAddress: String,
    val content: String,
    val updatedAt: Long,
    val accepted: Boolean = false,
)

@Entity(tableName = "send_request", indices = [Index(value = ["draftId", "draftRevision"], unique = true)])
data class MmsRequestEntity(
    @PrimaryKey val id: String,
    val draftId: String,
    val draftRevision: Long,
    val snapshot: String,
    val currentAttempt: String,
    val sourceId: Long? = null,
    val providerReady: Boolean = false,
    val providerSyncPending: Boolean = true,
    val deleted: Boolean = false,
    val possibleDuplicateOf: String? = null,
    val lateSuccess: Boolean = false,
    val originRequestId: String? = null,
)

@Entity(tableName = "send_attempt", indices = [Index(value = ["transactionId"], unique = true), Index("requestId")])
data class MmsAttemptEntity(
    @PrimaryKey val token: String,
    val requestId: String,
    val number: Int,
    val transactionId: String,
    val previousTransactionId: String? = null,
    val subscriptionId: Int,
    val state: String,
    val createdAt: Long,
    val pduPath: String? = null,
    val pduSha256: String? = null,
    val submittedAt: Long? = null,
    val resultCode: Int? = null,
    val httpStatus: Int? = null,
    val response: ByteArray? = null,
    val responseStatus: Int? = null,
    val messageId: String? = null,
    val confirmation: String? = null,
    val error: String? = null,
    val callbackRecorded: Boolean = false,
    val providerReady: Boolean = false,
)

@Entity(tableName = "callback_fact", indices = [Index(value = ["attemptToken", "fingerprint"], unique = true)])
data class MmsCallbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attemptToken: String,
    val fingerprint: String,
    val resultCode: Int,
    val httpStatus: Int?,
    val response: ByteArray?,
    val receivedAt: Long,
    val processed: Boolean = false,
    val resolvedState: String? = null,
    val responseStatus: Int? = null,
    val confirmation: String? = null,
)

@Entity(tableName = "file_cleanup")
data class MmsCleanupEntity(@PrimaryKey val path: String)

@Dao
interface OutgoingMmsDao {
    @Query("SELECT * FROM draft WHERE id = :id") suspend fun draft(id: String): MmsDraftEntity?
    @Query("SELECT * FROM draft WHERE id = :id") fun observeDraft(id: String): Flow<MmsDraftEntity?>
    @Query("SELECT * FROM draft WHERE accepted = 0 ORDER BY updatedAt DESC") fun observeDrafts(): Flow<List<MmsDraftEntity>>
    @Query("SELECT * FROM draft WHERE recipientAddress = :recipient AND accepted = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestDraft(recipient: String): MmsDraftEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertDraft(value: MmsDraftEntity)
    @Update suspend fun updateDraft(value: MmsDraftEntity)
    @Query("DELETE FROM draft WHERE id = :id") suspend fun deleteDraft(id: String)
    @Query("SELECT * FROM draft") suspend fun drafts(): List<MmsDraftEntity>
    @Query("SELECT * FROM send_request WHERE id = :id") suspend fun request(id: String): MmsRequestEntity?
    @Query("SELECT * FROM send_request WHERE draftId = :id AND draftRevision = :revision")
    suspend fun accepted(id: String, revision: Long): MmsRequestEntity?
    @Query("SELECT * FROM send_request") suspend fun requests(): List<MmsRequestEntity>
    @Query("SELECT * FROM send_request") fun observeRequests(): Flow<List<MmsRequestEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequest(value: MmsRequestEntity)
    @Update suspend fun updateRequest(value: MmsRequestEntity)
    @Query("SELECT * FROM send_attempt WHERE token = :token") suspend fun attempt(token: String): MmsAttemptEntity?
    @Query("SELECT * FROM send_attempt") suspend fun attempts(): List<MmsAttemptEntity>
    @Query("SELECT * FROM send_attempt") fun observeAttempts(): Flow<List<MmsAttemptEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttempt(value: MmsAttemptEntity)
    @Update suspend fun updateAttempt(value: MmsAttemptEntity)
    @Query("SELECT COUNT(*) FROM send_attempt WHERE subscriptionId = :subId AND state IN ('DISPATCHING', 'AWAITING_RESULT')")
    suspend fun activeForSubscription(subId: Int): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCallback(value: MmsCallbackEntity): Long
    @Query("SELECT * FROM callback_fact WHERE id = :id") suspend fun callback(id: Long): MmsCallbackEntity?
    @Query("SELECT f.* FROM callback_fact f JOIN send_attempt a ON a.token = f.attemptToken WHERE a.requestId = :requestId AND f.processed = 0 ORDER BY f.id")
    suspend fun pendingCallbacks(requestId: String): List<MmsCallbackEntity>
    @Update suspend fun updateCallback(value: MmsCallbackEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addCleanup(value: MmsCleanupEntity)
    @Query("SELECT * FROM file_cleanup") suspend fun cleanup(): List<MmsCleanupEntity>
    @Query("DELETE FROM file_cleanup WHERE path = :path") suspend fun removeCleanup(path: String)
}

@Database(entities = [MmsDraftEntity::class, MmsRequestEntity::class, MmsAttemptEntity::class, MmsCleanupEntity::class, MmsCallbackEntity::class], version = 1, exportSchema = true)
abstract class OutgoingMmsDatabase : RoomDatabase() {
    abstract fun dao(): OutgoingMmsDao
    companion object {
        fun create(context: Context): OutgoingMmsDatabase = Room.databaseBuilder(
            context.applicationContext, OutgoingMmsDatabase::class.java, "outgoing_mms.db",
        ).build()
    }
}
