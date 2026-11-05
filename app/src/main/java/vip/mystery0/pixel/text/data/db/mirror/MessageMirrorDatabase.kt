package vip.mystery0.pixel.text.data.db.mirror

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import vip.mystery0.pixel.text.data.db.ArchivedConversationDao
import vip.mystery0.pixel.text.data.db.ArchivedConversationEntity
import vip.mystery0.pixel.text.data.db.BlockedKeywordDao
import vip.mystery0.pixel.text.data.db.BlockedKeywordEntity
import vip.mystery0.pixel.text.data.db.SenderWhitelistDao
import vip.mystery0.pixel.text.data.db.SenderWhitelistRuleEntity
import vip.mystery0.pixel.text.data.db.SmsScanStateEntity
import vip.mystery0.pixel.text.data.db.VerificationCodeIndexDao
import vip.mystery0.pixel.text.data.db.VerificationCodeIndexEntity
import vip.mystery0.pixel.text.data.db.VerificationCodeMetadataEntity

@Database(
    entities = [MirrorMessageEntity::class, MirrorSmsEntity::class, MirrorMmsEntity::class,
        MirrorAddressEntity::class, MirrorPartEntity::class, MirrorAttachmentEntity::class,
        MirrorThreadSourceEntity::class, MirrorCanonicalAddressEntity::class, MirrorSyncStateEntity::class,
        MirrorDirtyEntity::class, MirrorFileCleanupEntity::class, MmsDownloadRequestEntity::class, MmsTextIndexEntity::class, DataInitializationEntity::class,
        BlockedKeywordEntity::class, SenderWhitelistRuleEntity::class, VerificationCodeIndexEntity::class,
        VerificationCodeMetadataEntity::class, SmsScanStateEntity::class, ArchivedConversationEntity::class],
    version = 7, exportSchema = true,
)
abstract class MessageMirrorDatabase : RoomDatabase() {
    abstract fun mirrorDao(): MirrorDao
    abstract fun syncDao(): MirrorSyncDao
    abstract fun searchDao(): MirrorSearchDao
    abstract fun initializationDao(): DataInitializationDao
    abstract fun spamDao(): MirrorSpamDao
    abstract fun blockedKeywordDao(): BlockedKeywordDao
    abstract fun senderWhitelistDao(): SenderWhitelistDao
    abstract fun verificationCodeIndexDao(): VerificationCodeIndexDao
    abstract fun archivedConversationDao(): ArchivedConversationDao

    suspend fun activateVerificationGeneration(generation: Long, ruleVersion: String, reconciledAt: Long) {
        withTransaction {
            verificationCodeIndexDao().upsertMetadata(VerificationCodeMetadataEntity(
                activeGeneration = generation, lastFullScanRuleVersion = ruleVersion,
                lastReconciledAt = reconciledAt,
            ))
            verificationCodeIndexDao().deleteOtherGenerations(generation)
            verificationCodeIndexDao().deleteOtherScanGenerations(generation)
        }
    }

    companion object {
        const val DATABASE_NAME = "message_mirror.db"
        // 后续版本必须显式注册 Migration，禁止破坏性重建。
        fun create(context: Context): MessageMirrorDatabase {
            val merge = MirrorAuxiliaryDatabaseMigration(context.applicationContext)
            return Room.databaseBuilder(
                context.applicationContext, MessageMirrorDatabase::class.java, DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MirrorSearchMigration, DataInitializationMigration,
                MIGRATION_4_5, MIGRATION_5_6, merge)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = merge.importLegacy(db)
                    override fun onOpen(db: SupportSQLiteDatabase) = merge.cleanImportedFiles(db)
                }).build()
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN isSpam INTEGER")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamSource INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamScore REAL")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamModelIdentity TEXT")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamModelRevision INTEGER")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamModelGeneration INTEGER")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamModelManual INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamKeywordId INTEGER")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamAllowedSource INTEGER")
                db.execSQL("ALTER TABLE mirror_message ADD COLUMN spamCorrection INTEGER")
                db.execSQL("CREATE INDEX index_mirror_message_isSpam_threadId_timestamp_localId ON mirror_message(isSpam, threadId, timestamp, localId)")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mirror_message_threadId_timestamp_localId ON mirror_message(threadId, timestamp, localId)")
            }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS mms_text_index (localId INTEGER NOT NULL, version INTEGER NOT NULL, fingerprint TEXT NOT NULL, summary TEXT NOT NULL, searchableText TEXT NOT NULL, PRIMARY KEY(localId), FOREIGN KEY(localId) REFERENCES mirror_message(localId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
    }
}
