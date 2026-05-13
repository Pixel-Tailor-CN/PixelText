package vip.mystery0.pixel.text.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SpamDatabase(context: Context) : SQLiteOpenHelper(context, "spam.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE spam_result (message_id INTEGER PRIMARY KEY, thread_id INTEGER NOT NULL, spam_score REAL NOT NULL, checked_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun getScore(messageId: Long): Float? {
        return readableDatabase.query(
            "spam_result", arrayOf("spam_score"),
            "message_id = ?", arrayOf(messageId.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getFloat(0) else null
        }
    }

    fun insert(messageId: Long, threadId: Long, score: Float) {
        val values = ContentValues().apply {
            put("message_id", messageId)
            put("thread_id", threadId)
            put("spam_score", score)
            put("checked_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "spam_result",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
