package vip.mystery0.pixel.text.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.SenderProfileMatch

@Entity(tableName = "cached_conversation")
data class CachedConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "thread_id") val threadId: Long,
    val address: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    val snippet: String,
    val timestamp: Long,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "is_mms") val isMms: Int,
    @ColumnInfo(name = "has_mms") val hasMms: Int,
)

@Entity(tableName = "conversation_cache_metadata")
data class CacheMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "metadata_key") val key: String,
    val value: Int,
)

@Entity(tableName = "sender_profile_generation")
data class SenderProfileGenerationEntity(
    @PrimaryKey val version: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
)

@Entity(
    tableName = "sender_profile_state",
    foreignKeys = [
        ForeignKey(
            entity = SenderProfileGenerationEntity::class,
            parentColumns = ["version"],
            childColumns = ["active_version"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = SenderProfileGenerationEntity::class,
            parentColumns = ["version"],
            childColumns = ["previous_version"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("active_version"), Index("previous_version")],
)
data class SenderProfileStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "active_version") val activeVersion: String?,
    @ColumnInfo(name = "previous_version") val previousVersion: String?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "sender_profile",
    foreignKeys = [
        ForeignKey(
            entity = SenderProfileGenerationEntity::class,
            parentColumns = ["version"],
            childColumns = ["generation_version"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("generation_version"),
        Index(value = ["generation_version", "id"], unique = true),
    ],
)
data class SenderProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "generation_version") val generationVersion: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "avatar_path") val avatarPath: String,
    @ColumnInfo(name = "avatar_sha256") val avatarSha256: String,
)

@Entity(
    tableName = "sender_profile_number",
    primaryKeys = ["generation_version", "number"],
    foreignKeys = [
        ForeignKey(
            entity = SenderProfileGenerationEntity::class,
            parentColumns = ["version"],
            childColumns = ["generation_version"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SenderProfileEntity::class,
            parentColumns = ["generation_version", "id"],
            childColumns = ["generation_version", "sender_profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("generation_version"),
        Index(value = ["generation_version", "sender_profile_id"]),
    ],
)
data class SenderProfileNumberEntity(
    @ColumnInfo(name = "generation_version") val generationVersion: String,
    val number: String,
    @ColumnInfo(name = "sender_profile_id") val senderProfileId: Long,
)

data class CachedConversationWithSenderProfile(
    @Embedded val conversation: CachedConversationEntity,
    @ColumnInfo(name = "cloud_display_name") val cloudDisplayName: String?,
    @ColumnInfo(name = "cloud_avatar_path") val cloudAvatarPath: String?,
    @ColumnInfo(name = "cloud_avatar_sha256") val cloudAvatarSha256: String?,
)

data class SenderProfileMatchRow(
    val number: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "avatar_path") val avatarPath: String,
    @ColumnInfo(name = "avatar_sha256") val avatarSha256: String,
)

@Dao
interface CachedConversationDao {
    @Query("SELECT * FROM cached_conversation ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getConversations(limit: Int, offset: Int): List<CachedConversationEntity>

    @Query("SELECT * FROM cached_conversation ORDER BY timestamp DESC")
    suspend fun getAllConversations(): List<CachedConversationEntity>

    @Query(
        """
        SELECT conversation.*,
            profile.display_name AS cloud_display_name,
            profile.avatar_path AS cloud_avatar_path,
            profile.avatar_sha256 AS cloud_avatar_sha256
        FROM cached_conversation AS conversation
        LEFT JOIN sender_profile_state AS state ON state.id = 1
        LEFT JOIN sender_profile_number AS number
            ON number.generation_version = state.active_version
            AND number.number = conversation.address
        LEFT JOIN sender_profile AS profile
            ON profile.id = number.sender_profile_id
            AND profile.generation_version = number.generation_version
        ORDER BY conversation.timestamp DESC
        """
    )
    fun observeAllConversationsWithSenderProfile(): Flow<List<CachedConversationWithSenderProfile>>

    @Query("SELECT thread_id FROM cached_conversation")
    suspend fun getAllThreadIds(): List<Long>

    @Query("SELECT COUNT(*) FROM cached_conversation")
    suspend fun count(): Int

    @Query("SELECT value FROM conversation_cache_metadata WHERE metadata_key = :key")
    suspend fun getMetadataValue(key: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversations: List<CachedConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: CacheMetadataEntity)

    @Query("DELETE FROM cached_conversation WHERE thread_id IN (:threadIds)")
    suspend fun delete(threadIds: Set<Long>)

    @Query("DELETE FROM cached_conversation")
    suspend fun deleteAll()
}

@Dao
interface SenderProfileDao {
    @Query("SELECT * FROM sender_profile_state WHERE id = 1")
    suspend fun getState(): SenderProfileStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: SenderProfileStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeneration(generation: SenderProfileGenerationEntity)

    @Insert
    suspend fun insertProfile(profile: SenderProfileEntity): Long

    @Insert
    suspend fun insertNumbers(numbers: List<SenderProfileNumberEntity>)

    @Query(
        """
        SELECT number.number, profile.display_name, profile.avatar_path, profile.avatar_sha256
        FROM sender_profile_state AS state
        JOIN sender_profile_number AS number ON number.generation_version = state.active_version
        JOIN sender_profile AS profile
            ON profile.id = number.sender_profile_id
            AND profile.generation_version = number.generation_version
        WHERE state.id = 1 AND number.number = :address
        LIMIT 1
        """
    )
    suspend fun findActiveByNumber(address: String): SenderProfileMatchRow?

    @Query(
        """
        SELECT number.number, profile.display_name, profile.avatar_path, profile.avatar_sha256
        FROM sender_profile_state AS state
        JOIN sender_profile_number AS number ON number.generation_version = state.active_version
        JOIN sender_profile AS profile
            ON profile.id = number.sender_profile_id
            AND profile.generation_version = number.generation_version
        WHERE state.id = 1 AND number.number IN (:addresses)
        """
    )
    suspend fun findActiveByNumbers(addresses: List<String>): List<SenderProfileMatchRow>

    @Query("SELECT version FROM sender_profile_generation ORDER BY imported_at DESC")
    suspend fun getGenerationVersions(): List<String>

    @Query("DELETE FROM sender_profile_generation WHERE version IN (:versions)")
    suspend fun deleteGenerations(versions: List<String>)
}

@Database(
    entities = [
        CachedConversationEntity::class,
        CacheMetadataEntity::class,
        SenderProfileGenerationEntity::class,
        SenderProfileStateEntity::class,
        SenderProfileEntity::class,
        SenderProfileNumberEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ConversationCacheDatabase : RoomDatabase() {
    abstract fun cachedConversationDao(): CachedConversationDao
    abstract fun senderProfileDao(): SenderProfileDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_cache_metadata` (
                        `metadata_key` TEXT NOT NULL,
                        `value` INTEGER NOT NULL,
                        PRIMARY KEY(`metadata_key`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sender_profile_generation` (`version` TEXT NOT NULL, `imported_at` INTEGER NOT NULL, PRIMARY KEY(`version`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sender_profile_state` (`id` INTEGER NOT NULL, `active_version` TEXT, `previous_version` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`active_version`) REFERENCES `sender_profile_generation`(`version`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`previous_version`) REFERENCES `sender_profile_generation`(`version`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sender_profile_state_active_version` ON `sender_profile_state` (`active_version`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sender_profile_state_previous_version` ON `sender_profile_state` (`previous_version`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sender_profile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `generation_version` TEXT NOT NULL, `display_name` TEXT NOT NULL, `avatar_path` TEXT NOT NULL, `avatar_sha256` TEXT NOT NULL, FOREIGN KEY(`generation_version`) REFERENCES `sender_profile_generation`(`version`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sender_profile_generation_version` ON `sender_profile` (`generation_version`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sender_profile_generation_version_id` ON `sender_profile` (`generation_version`, `id`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sender_profile_number` (`generation_version` TEXT NOT NULL, `number` TEXT NOT NULL, `sender_profile_id` INTEGER NOT NULL, PRIMARY KEY(`generation_version`, `number`), FOREIGN KEY(`generation_version`) REFERENCES `sender_profile_generation`(`version`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`generation_version`, `sender_profile_id`) REFERENCES `sender_profile`(`generation_version`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sender_profile_number_generation_version` ON `sender_profile_number` (`generation_version`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sender_profile_number_generation_version_sender_profile_id` ON `sender_profile_number` (`generation_version`, `sender_profile_id`)")
            }
        }

        fun create(context: Context): ConversationCacheDatabase {
            return Room.databaseBuilder(
                context,
                ConversationCacheDatabase::class.java,
                "conversation_cache.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}

fun CachedConversationWithSenderProfile.toConversationModel() = conversation.toConversationModel(
    cloudDisplayName = cloudDisplayName,
    avatarPath = cloudAvatarPath,
    avatarSha256 = cloudAvatarSha256,
)

fun CachedConversationEntity.toConversationModel(
    cloudDisplayName: String? = null,
    avatarPath: String? = null,
    avatarSha256: String? = null,
) = ConversationModel(
    threadId = threadId,
    address = address,
    displayName = displayName ?: cloudDisplayName,
    snippet = snippet,
    timestamp = timestamp,
    unreadCount = unreadCount,
    isMms = isMms == 1,
    hasMms = hasMms == 1,
    avatarPath = avatarPath,
    avatarSha256 = avatarSha256,
)

fun SenderProfileMatchRow.toDomain() = SenderProfileMatch(
    displayName = displayName,
    avatarPath = avatarPath,
    avatarSha256 = avatarSha256,
)

fun ConversationModel.toCachedConversationEntity() = CachedConversationEntity(
    threadId = threadId,
    address = address,
    displayName = displayName,
    snippet = snippet,
    timestamp = timestamp,
    unreadCount = unreadCount,
    isMms = if (isMms) 1 else 0,
    hasMms = if (hasMms) 1 else 0,
)
