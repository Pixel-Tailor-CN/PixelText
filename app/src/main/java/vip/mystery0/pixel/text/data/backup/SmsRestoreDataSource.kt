package vip.mystery0.pixel.text.data.backup

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.provider.Telephony
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.data.db.ArchivedConversationEntity
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.db.SpamAllowedMessageEntity
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.source.WhitelistMessageSource
import vip.mystery0.pixel.text.domain.backup.BackupSummary
import java.io.File
import java.security.MessageDigest

/** 仅恢复时访问目标 Provider；备份数据来自隔离的应用镜像快照。 */
class SmsRestoreDataSource(
    private val context: Context,
    private val spam: SpamDatabase,
    private val archive: ConversationArchiveDatabase,
    private val identities: WhitelistMessageSource,
    private val safety: RestoreSafetyCoordinator,
) {
    private val resolver get() = context.contentResolver
    fun requireAccess() {
        backupRequire(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED, "请授予读取短信权限")
        backupRequire(context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_SMS), "请先将 Pixel Text 设置为默认短信应用")
    }

    suspend fun restore(directory: File, initial: BackupSummary, progress: (BackupSummary) -> Unit): BackupSummary {
        requireAccess()
        val indexFile = File(directory, "restore-index.db").also { it.delete() }
        var result = initial
        SQLiteDatabase.openOrCreateDatabase(indexFile, null).use { index ->
            index.execSQL("CREATE TABLE candidates(id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0)")
            index.execSQL("CREATE INDEX candidate_key ON candidates(fingerprint,used,id)")
            index.execSQL("CREATE TABLE restored(backup_id INTEGER PRIMARY KEY, target_id INTEGER NOT NULL, thread_id INTEGER)")
            index.beginTransaction()
            try {
                val cursor = resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type"), null, null, "_id ASC")
                    ?: throw BackupException("无法读取目标短信")
                cursor.use { c ->
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val key = messageKey(c.stringOrNull("address"), c.stringOrNull("body"), c.longOrNull("date")!!, c.longOrNull("type")!!.toInt())
                        index.execSQL("INSERT INTO candidates(id,fingerprint) VALUES(?,?)", arrayOf<Any?>(c.longOrNull("_id"), key))
                    }
                }
                index.setTransactionSuccessful()
            } finally { index.endTransaction() }
            open(directory, "message_mirror.db").use { source ->
                open(directory, "spam.db").use { states ->
                    open(directory, "conversation_archive.db").use { archived ->
                        source.rawQuery("SELECT m.*,s.* FROM mirror_message m JOIN mirror_sms s ON m.localId=s.localId ORDER BY m.localId", null).use { c ->
                            while (c.moveToNext()) {
                                currentCoroutineContext().ensureActive()
                                requireAccess()
                                val address = c.stringOrNull("address")
                                val body = c.stringOrNull("body")
                                val date = c.longOrNull("originalDate")!!
                                val type = c.longOrNull("boxType")!!.toInt()
                                val key = messageKey(address, body, date, type)
                                var targetId: Long? = null
                                while (true) {
                                    val candidate = index.rawQuery("SELECT id FROM candidates WHERE fingerprint=? AND used=0 ORDER BY id LIMIT 1", arrayOf(key)).use {
                                        if (it.moveToFirst()) it.getLong(0) else null
                                    } ?: break
                                    index.execSQL("UPDATE candidates SET used=1 WHERE id=?", arrayOf(candidate))
                                    // 摘要仅索引使用，精确核对当前字段，避免 ID 复用和摘要碰撞。
                                    if (matches(candidate, address, body, date, type)) { targetId = candidate; break }
                                }
                                val existing = targetId != null
                                try {
                                    if (targetId == null) {
                                        val values = ContentValues().apply {
                                            put("address", address); put("body", body); put("date", date)
                                            put("type", normalizedType(type)); put("read", c.longOrNull("read")); put("seen", c.longOrNull("seen"))
                                            put("sub_id", -1)
                                            for ((column, original) in listOf("date_sent" to "dateSent", "locked" to "locked", "reply_path_present" to "replyPathPresent")) {
                                                c.longOrNull(original)?.let { put(column, it) }
                                            }
                                            c.longOrNull("status")?.let { put("status", if (type == 4 || type == 6) -1L else it) }
                                            put("subject", c.stringOrNull("subject")); put("service_center", c.stringOrNull("serviceCenter"))
                                        }
                                        val uri = safety.insertHistorical(values) {
                                            resolver.insert(Telephony.Sms.CONTENT_URI, values) ?: throw BackupException("系统拒绝写入短信")
                                        }
                                        targetId = ContentUris.parseId(uri)
                                    }
                                    result = result.copy(
                                        inserted = result.inserted + if (existing) 0 else 1,
                                        existing = result.existing + if (existing) 1 else 0,
                                        remaining = (result.remaining - 1).coerceAtLeast(0),
                                        convertedOutgoing = result.convertedOutgoing + if (!existing && (type == 4 || type == 6)) 1 else 0,
                                    )
                                    // 先报告已提交的 Provider 写入，后续关联失败也不能误算为未写入。
                                    progress(result)
                                    val thread = resolver.query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, targetId), arrayOf("thread_id"), null, null, null)?.use {
                                        if (it.moveToFirst()) it.getLong(0) else null
                                    }
                                    index.execSQL("INSERT INTO restored VALUES(?,?,?)", arrayOf(c.longOrNull("localId"), targetId, thread))
                                    val skipped = restoreState(c, targetId, thread, states, archived)
                                    if (skipped > 0) { result = result.copy(skippedAssociations = result.skippedAssociations + skipped); progress(result) }
                                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                                catch (error: Exception) {
                                    if (targetId == null) {
                                        result = result.copy(failed = result.failed + 1, remaining = (result.remaining - 1).coerceAtLeast(0))
                                        progress(result)
                                    }
                                    throw error
                                }
                            }
                        }
                    }
                }
            }
        }
        indexFile.delete()
        return result
    }

    private fun matches(id: Long, address: String?, body: String?, date: Long, type: Int): Boolean =
        resolver.query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), arrayOf("address", "body", "date", "type"), null, null, null)?.use {
            it.moveToFirst() && it.stringOrNull("address") == address && it.stringOrNull("body") == body &&
                it.longOrNull("date") == date && normalizedType(it.longOrNull("type")!!.toInt()) == normalizedType(type)
        } ?: false

    private suspend fun restoreState(c: Cursor, id: Long, thread: Long?, states: SQLiteDatabase, archived: SQLiteDatabase): Long {
        val oldId = c.longOrNull("sourceId")!!
        val oldFingerprint = fingerprint("$oldId:${c.stringOrNull("address").orEmpty().length}:${c.stringOrNull("address").orEmpty()}:${c.longOrNull("originalDate")}:${c.longOrNull("dateSent") ?: 0}")
        var skipped = 0L
        states.rawQuery("SELECT fingerprint,allowed_at FROM spam_allowed_message WHERE message_id=?", arrayOf(oldId.toString())).use { row ->
            if (row.moveToFirst()) {
                if (row.getString(0) != oldFingerprint) skipped++
                else {
                    val identity = identities.load(listOf(id)).singleOrNull()
                    if (identity == null) skipped++
                    else spam.senderWhitelistDao().allow(listOf(SpamAllowedMessageEntity(id, identity.fingerprint, row.getLong(1))))
                }
            }
        }
        val oldThread = c.longOrNull("threadId")
        val archivedAt = archived.rawQuery("SELECT archived_at FROM archived_conversation WHERE thread_id=?", arrayOf(oldThread.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
        if (archivedAt != null) {
            if (thread == null || thread <= 0) skipped++
            else {
                val row = resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date"), "thread_id=?", arrayOf(thread.toString()), "date DESC, _id DESC")?.use {
                    if (!it.moveToFirst()) null else Triple(it.getString(0).orEmpty(), it.getString(1).orEmpty(), it.getLong(2))
                }
                if (row == null) skipped++
                else {
                    val unread = resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "thread_id=? AND read=0", arrayOf(thread.toString()), null)?.use { it.count } ?: 0
                    val hasMms = resolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), "thread_id=?", arrayOf(thread.toString()), null)?.use { it.moveToFirst() } ?: false
                    archive.archivedConversationDao().archive(listOf(ArchivedConversationEntity(thread, row.first, null, row.second, row.third, unread, 0, if (hasMms) 1 else 0, archivedAt)))
                }
            }
        }
        return skipped
    }

    private fun open(directory: File, name: String) = SQLiteDatabase.openDatabase(File(directory, "databases/$name").path, null, SQLiteDatabase.OPEN_READONLY)
    private fun normalizedType(type: Int): Int = if (type == 4 || type == 6) 5 else type
    private fun messageKey(address: String?, body: String?, date: Long, type: Int): String {
        fun field(value: String?) = if (value == null) "-1:" else "${value.length}:$value"
        return fingerprint("${field(address)}${field(body)}:$date:${normalizedType(type)}")
    }
    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
