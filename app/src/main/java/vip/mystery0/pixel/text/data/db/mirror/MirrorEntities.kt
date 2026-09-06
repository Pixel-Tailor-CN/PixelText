package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.*

@Entity(tableName = "mirror_message", indices = [Index(value = ["transport", "sourceId"], unique = true), Index("threadId"), Index("timestamp"), Index("subscriptionId")])
data class MirrorMessageEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val transport: String, val sourceId: Long, val revision: Long = 1,
    val threadId: Long?, val timestamp: Long?, val originalDate: Long?, val dateUnit: String,
    val subscriptionId: Int?, val boxType: Int?, val read: Int?, val seen: Int?,
    val structureComplete: Boolean, val generation: Long = 0,
)

@Entity(tableName = "mirror_sms", foreignKeys = [ForeignKey(entity = MirrorMessageEntity::class, parentColumns = ["localId"], childColumns = ["localId"], onDelete = ForeignKey.CASCADE)], indices = [Index("address")])
data class MirrorSmsEntity(
    @PrimaryKey val localId: Long, val rawSnapshot: String, val body: String?, val address: String?,
    val subject: String?, val dateSent: Long?, val status: Int?, val errorCode: Int?, val locked: Int?,
    val protocol: Int?, val replyPathPresent: Int?, val serviceCenter: String?, val creator: String?,
)

@Entity(tableName = "mirror_mms", foreignKeys = [ForeignKey(entity = MirrorMessageEntity::class, parentColumns = ["localId"], childColumns = ["localId"], onDelete = ForeignKey.CASCADE)])
data class MirrorMmsEntity(
    @PrimaryKey val localId: Long, val rawSnapshot: String, val subject: String?, val subjectCharset: Int?,
    val decodedSubject: String?, val pduType: Int?, val downloadStatus: Int?, val transactionId: String?,
    val contentLocation: String?, val expiry: Long?, val messageSize: Long?,
)

@Entity(tableName = "mirror_mms_address", primaryKeys = ["localId", "ordinal"], foreignKeys = [ForeignKey(entity = MirrorMessageEntity::class, parentColumns = ["localId"], childColumns = ["localId"], onDelete = ForeignKey.CASCADE)], indices = [Index("normalizedAddress")])
data class MirrorAddressEntity(
    val localId: Long, val ordinal: Int, val sourceId: Long?, val type: Int?, val charset: Int?,
    val address: String?, val normalizedAddress: String?, val rawSnapshot: String,
)

@Entity(tableName = "mirror_mms_part", primaryKeys = ["localId", "sourceId"], foreignKeys = [ForeignKey(entity = MirrorMessageEntity::class, parentColumns = ["localId"], childColumns = ["localId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sourceId")])
data class MirrorPartEntity(
    val localId: Long, val sourceId: Long, val sequence: Int?, val mimeType: String?, val charset: Int?,
    val name: String?, val filename: String?, val contentId: String?, val contentLocation: String?,
    val text: String?, val rawSnapshot: String,
)

@Entity(tableName = "mirror_attachment", primaryKeys = ["localId", "partId"], foreignKeys = [ForeignKey(entity = MirrorPartEntity::class, parentColumns = ["localId", "sourceId"], childColumns = ["localId", "partId"], onDelete = ForeignKey.CASCADE)], indices = [Index("state"), Index("relativePath")])
data class MirrorAttachmentEntity(
    val localId: Long, val partId: Long, val revision: Long, val sourceUri: String,
    val relativePath: String? = null, val byteCount: Long? = null, val sha256: String? = null,
    val state: String, val error: String? = null, val attempts: Int = 0, val retryAfter: Long = 0,
    val verifiedAt: Long? = null,
)

@Entity(tableName = "mirror_thread_source")
data class MirrorThreadSourceEntity(@PrimaryKey val sourceId: Long, val rawSnapshot: String)

@Entity(tableName = "mirror_canonical_address", indices = [Index("address")])
data class MirrorCanonicalAddressEntity(@PrimaryKey val sourceId: Long, val address: String?, val rawSnapshot: String)

@Entity(tableName = "mirror_sync_state")
data class MirrorSyncStateEntity(
    @PrimaryKey val collection: String, val generation: Long = 0, val checkpoint: Long? = null,
    val complete: Boolean = false, val running: Boolean = false, val lastSuccessTime: Long? = null,
    val error: String? = null, val columns: String = "[]", val processedCount: Long = 0,
    val completedSources: String = "[]", val dirtyToken: String? = null,
)

@Entity(tableName = "mirror_sync_dirty")
data class MirrorDirtyEntity(@PrimaryKey val key: String, val transport: String?, val sourceId: Long?, val token: String)

@Entity(tableName = "mirror_file_cleanup")
data class MirrorFileCleanupEntity(@PrimaryKey val relativePath: String, val attempts: Int = 0)

@Entity(tableName = "mms_download_request", indices = [Index("sourceMmsId")])
data class MmsDownloadRequestEntity(
    @PrimaryKey val token: String, val sourceMmsId: Long, val temporaryPath: String,
    val stage: String, val subscriptionId: Int?, val updatedAt: Long, val error: String? = null,
)

data class MirrorMessageRecord(
    @Embedded val message: MirrorMessageEntity,
    @Relation(parentColumn = "localId", entityColumn = "localId") val sms: MirrorSmsEntity?,
    @Relation(parentColumn = "localId", entityColumn = "localId") val mms: MirrorMmsEntity?,
    @Relation(parentColumn = "localId", entityColumn = "localId") val addresses: List<MirrorAddressEntity>,
    @Relation(parentColumn = "localId", entityColumn = "localId") val parts: List<MirrorPartEntity>,
    @Relation(parentColumn = "localId", entityColumn = "localId") val attachments: List<MirrorAttachmentEntity>,
)

data class MirrorStructure(
    val message: MirrorMessageEntity, val sms: MirrorSmsEntity?, val mms: MirrorMmsEntity?,
    val addresses: List<MirrorAddressEntity>, val parts: List<MirrorPartEntity>,
    val attachments: List<MirrorAttachmentEntity>, val childrenComplete: Boolean,
    val sourceColumns: Set<String> = emptySet(),
)

data class MirrorConversationRow(
    val threadId: Long, val address: String?, val snippet: String?, val timestamp: Long?,
    val unreadCount: Int, val latestIsMms: Boolean, val containsMms: Boolean,
)
