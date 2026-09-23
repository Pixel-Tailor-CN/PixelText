package vip.mystery0.pixel.text.data.db.mirror

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MirrorMessageEntity::class, MirrorSmsEntity::class, MirrorMmsEntity::class,
        MirrorAddressEntity::class, MirrorPartEntity::class, MirrorAttachmentEntity::class,
        MirrorThreadSourceEntity::class, MirrorCanonicalAddressEntity::class, MirrorSyncStateEntity::class,
        MirrorDirtyEntity::class, MirrorFileCleanupEntity::class, MmsDownloadRequestEntity::class, MmsTextIndexEntity::class, DataInitializationEntity::class],
    version = 4, exportSchema = true,
)
abstract class MessageMirrorDatabase : RoomDatabase() {
    abstract fun mirrorDao(): MirrorDao
    abstract fun syncDao(): MirrorSyncDao
    abstract fun searchDao(): MirrorSearchDao
    abstract fun initializationDao(): DataInitializationDao

    companion object {
        const val DATABASE_NAME = "message_mirror.db"
        // 后续版本必须显式注册 Migration，禁止破坏性重建。
        fun create(context: Context): MessageMirrorDatabase = Room.databaseBuilder(
            context.applicationContext, MessageMirrorDatabase::class.java, DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MirrorSearchMigration, DataInitializationMigration).build()

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS mms_text_index (localId INTEGER NOT NULL, version INTEGER NOT NULL, fingerprint TEXT NOT NULL, summary TEXT NOT NULL, searchableText TEXT NOT NULL, PRIMARY KEY(localId), FOREIGN KEY(localId) REFERENCES mirror_message(localId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
    }
}
