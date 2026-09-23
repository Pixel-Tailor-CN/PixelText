package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 保留原有镜像与索引，旧安装缺少状态行自然视为数据版本 0。 */
object DataInitializationMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS data_initialization (
                id INTEGER NOT NULL PRIMARY KEY,
                completedVersion INTEGER NOT NULL,
                targetVersion INTEGER NOT NULL,
                nextPhase TEXT NOT NULL,
                status TEXT NOT NULL,
                epoch INTEGER NOT NULL,
                mirrorRound INTEGER,
                afterLocalId INTEGER NOT NULL,
                upperLocalId INTEGER,
                errorCategory TEXT,
                skipReason TEXT,
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent()
        )
    }
}
