package vip.mystery0.pixel.text.data.db.mirror

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MirrorMessageEntity::class, MirrorSmsEntity::class, MirrorMmsEntity::class,
        MirrorAddressEntity::class, MirrorPartEntity::class, MirrorAttachmentEntity::class,
        MirrorThreadSourceEntity::class, MirrorCanonicalAddressEntity::class, MirrorSyncStateEntity::class,
        MirrorDirtyEntity::class, MirrorFileCleanupEntity::class, MmsDownloadRequestEntity::class],
    version = 1, exportSchema = true,
)
abstract class MessageMirrorDatabase : RoomDatabase() {
    abstract fun mirrorDao(): MirrorDao
    abstract fun syncDao(): MirrorSyncDao

    companion object {
        const val DATABASE_NAME = "message_mirror.db"
        // 后续版本必须显式注册 Migration，禁止破坏性重建。
        fun create(context: Context): MessageMirrorDatabase = Room.databaseBuilder(
            context.applicationContext, MessageMirrorDatabase::class.java, DATABASE_NAME,
        ).build()
    }
}
