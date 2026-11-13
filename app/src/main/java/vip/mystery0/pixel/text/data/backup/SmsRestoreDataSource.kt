package vip.mystery0.pixel.text.data.backup

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.provider.Telephony
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.db.ArchivedConversationEntity
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamLegacyImporter
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.source.requireSmsAccess
import vip.mystery0.pixel.text.domain.backup.BackupSummary
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.spam.MessageSpamSource
import java.io.File
import java.security.MessageDigest

/** 仅恢复时访问目标 Provider；备份数据来自隔离的应用镜像快照。 */
class SmsRestoreDataSource(
    private val context: Context,
    private val mirror: MessageMirrorDatabase,
    private val importer: MirrorSpamLegacyImporter,
    private val synchronizer: MessageMirrorSynchronizer,
    private val safety: RestoreSafetyCoordinator,
) {
    private val resolver get() = context.contentResolver

    fun requireAccess() = context.requireSmsAccess()

    suspend fun restore(directory: File, initial: BackupSummary, progress: (BackupSummary) -> Unit): BackupSummary {
        requireAccess()
        importer.ensureImported()
        val indexFile = File(directory, "restore-index.db").also { it.delete() }
        var result = initial
        SQLiteDatabase.openOrCreateDatabase(indexFile, null).use { index ->
            index.execSQL("CREATE TABLE candidates(id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0)")
            index.execSQL("CREATE INDEX candidate_key ON candidates(fingerprint,used,id)")
            index.execSQL("CREATE TABLE restored(backup_id INTEGER PRIMARY KEY, target_id INTEGER NOT NULL, thread_id INTEGER)")
            index.transaction {
                val cursor = resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf("_id", "address", "body", "date", "type"),
                    null,
                    null,
                    "_id ASC",
                ) ?: throw BackupException(context.getString(R.string.domain_backup_error_read_target_sms))
                cursor.use { c ->
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val key = messageKey(
                            c.stringOrNull("address"),
                            c.stringOrNull("body"),
                            c.longOrNull("date")!!,
                            c.longOrNull("type")!!.toInt(),
                        )
                        index.execSQL(
                            "INSERT INTO candidates(id,fingerprint) VALUES(?,?)",
                            arrayOf<Any?>(c.longOrNull("_id"), key),
                        )
                    }
                }
            }
            open(directory, "message_mirror.db").use { source ->
                open(directory, "spam.db").use { states ->
                    open(directory, "conversation_archive.db").use { archived ->
                        source.rawQuery(
                            "SELECT m.*,s.* FROM mirror_message m JOIN mirror_sms s ON m.localId=s.localId ORDER BY m.localId",
                            null,
                        ).use { c ->
                            val pending = ArrayList<PendingInsert>(RESTORE_BATCH_SIZE)
                            var processed = 0
                            while (c.moveToNext()) {
                                currentCoroutineContext().ensureActive()
                                if (processed % RESTORE_BATCH_SIZE == 0) requireAccess()
                                val address = c.stringOrNull("address")
                                val body = c.stringOrNull("body")
                                val date = c.longOrNull("originalDate")!!
                                val type = c.longOrNull("boxType")!!.toInt()
                                val record = RestoreRecord(
                                    localId = c.longOrNull("localId")!!,
                                    sourceId = c.longOrNull("sourceId")!!,
                                    address = address,
                                    body = body,
                                    originalDate = date,
                                    dateSent = c.longOrNull("dateSent"),
                                    threadId = c.longOrNull("threadId"),
                                )
                                val key = messageKey(address, body, date, type)
                                var targetId: Long? = null
                                while (true) {
                                    val candidate = index.rawQuery(
                                        "SELECT id FROM candidates WHERE fingerprint=? AND used=0 ORDER BY id LIMIT 1",
                                        arrayOf(key),
                                    ).use {
                                        if (it.moveToFirst()) it.getLong(0) else null
                                    } ?: break
                                    index.execSQL("UPDATE candidates SET used=1 WHERE id=?", arrayOf(candidate))
                                    // 摘要仅索引使用，精确核对当前字段，避免 ID 复用和摘要碰撞。
                                    if (matches(candidate, address, body, date, type)) {
                                        targetId = candidate
                                        break
                                    }
                                }
                                if (targetId == null) {
                                    pending += PendingInsert(record, type, values(c, address, body, date, type))
                                    if (pending.size == RESTORE_BATCH_SIZE) {
                                        result = insertPending(pending, result, index, states, archived, progress)
                                        pending.clear()
                                    }
                                } else {
                                    try {
                                        result = commitRestored(
                                            record = record,
                                            id = targetId,
                                            type = type,
                                            existing = true,
                                            result = result,
                                            index = index,
                                            states = states,
                                            archived = archived,
                                            progress = progress,
                                        )
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        throw error
                                    }
                                }
                                processed++
                            }
                            if (pending.isNotEmpty()) {
                                result = insertPending(pending, result, index, states, archived, progress)
                            }
                        }
                    }
                }
            }
        }
        indexFile.delete()
        return result
    }

    private suspend fun insertPending(
        pending: List<PendingInsert>,
        initial: BackupSummary,
        index: SQLiteDatabase,
        states: SQLiteDatabase,
        archived: SQLiteDatabase,
        progress: (BackupSummary) -> Unit,
    ): BackupSummary {
        requireAccess()
        val operations = ArrayList<ContentProviderOperation>(pending.size).apply {
            pending.forEach {
                add(ContentProviderOperation.newInsert(Telephony.Sms.CONTENT_URI)
                    .withValues(it.values)
                    .build())
            }
        }
        val results = safety.insertHistoricalBatch(pending.map { it.values }) {
            resolver.applyBatch("sms", operations)
        }
        backupRequire(results.size == pending.size, context.getString(R.string.domain_backup_error_batch_insert_count_mismatch))
        var result = initial
        pending.forEachIndexed { indexInBatch, item ->
            val uri = results[indexInBatch].uri
                ?: throw BackupException(context.getString(R.string.domain_backup_error_missing_sms_uri))
            result = commitRestored(
                record = item.record,
                id = ContentUris.parseId(uri),
                type = item.type,
                existing = false,
                result = result,
                index = index,
                states = states,
                archived = archived,
                progress = progress,
            )
        }
        return result
    }

    private suspend fun commitRestored(
        record: RestoreRecord,
        id: Long,
        type: Int,
        existing: Boolean,
        result: BackupSummary,
        index: SQLiteDatabase,
        states: SQLiteDatabase,
        archived: SQLiteDatabase,
        progress: (BackupSummary) -> Unit,
    ): BackupSummary {
        var updated = result.copy(
            inserted = result.inserted + if (existing) 0 else 1,
            existing = result.existing + if (existing) 1 else 0,
            remaining = (result.remaining - 1).coerceAtLeast(0),
            convertedOutgoing = result.convertedOutgoing + if (!existing && (type == 4 || type == 6)) 1 else 0,
        )
        // 先报告已提交的 Provider 写入，后续关联失败也不能误算为未写入。
        progress(updated)
        index.execSQL("INSERT INTO restored VALUES(?,?,?)", arrayOf(record.localId, id, null))
        val skipped = restoreState(record, id, states, archived)
        if (skipped > 0) {
            updated = updated.copy(skippedAssociations = updated.skippedAssociations + skipped)
            progress(updated)
        }
        return updated
    }

    private fun values(c: Cursor, address: String?, body: String?, date: Long, type: Int) = ContentValues().apply {
        put("address", address)
        put("body", body)
        put("date", date)
        put("type", normalizedType(type))
        put("read", c.longOrNull("read"))
        put("seen", c.longOrNull("seen"))
        put("sub_id", -1)
        for ((column, original) in listOf(
            "date_sent" to "dateSent",
            "locked" to "locked",
            "reply_path_present" to "replyPathPresent",
        )) {
            c.longOrNull(original)?.let { put(column, it) }
        }
        c.longOrNull("status")?.let { put("status", if (type == 4 || type == 6) -1L else it) }
        put("subject", c.stringOrNull("subject"))
        put("service_center", c.stringOrNull("serviceCenter"))
    }

    private fun matches(id: Long, address: String?, body: String?, date: Long, type: Int): Boolean =
        resolver.query(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
            arrayOf("address", "body", "date", "type"),
            null,
            null,
            null,
        )?.use {
            it.moveToFirst() && it.stringOrNull("address") == address && it.stringOrNull("body") == body &&
                it.longOrNull("date") == date && normalizedType(it.longOrNull("type")!!.toInt()) == normalizedType(type)
        } ?: false

    private suspend fun restoreState(record: RestoreRecord, id: Long, states: SQLiteDatabase, archived: SQLiteDatabase): Long {
        val oldFingerprint = fingerprint(
            "${record.sourceId}:${record.address.orEmpty().length}:${record.address.orEmpty()}:${record.originalDate}:${record.dateSent ?: 0}",
        )
        var skipped = 0L
        states.rawQuery(
            "SELECT fingerprint,allowed_at FROM spam_allowed_message WHERE message_id=?",
            arrayOf(record.sourceId.toString()),
        ).use { row ->
            if (row.moveToFirst()) {
                if (row.getString(0) != oldFingerprint) skipped++
                else {
                    // 先同步实际目标 ID，避免向尚不存在的镜像行写标记而静默丢失。
                    val refreshed = synchronizer.refreshInitializationMessage(SourceMessageKey(MessageTransport.SMS, id))
                    val state = if (refreshed) mirror.spamDao().rows(listOf(id)).singleOrNull() else null
                    if (state == null) skipped++
                    else mirror.spamDao().allow(
                        listOf(id),
                        MessageSpamSource.RELEASED,
                        mapOf(id to (state.localId to state.messageRevision)),
                    )
                }
            }
        }
        val archivedAt = archived.rawQuery(
            "SELECT archived_at FROM archived_conversation WHERE thread_id=?",
            arrayOf(record.threadId.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (archivedAt != null) {
            // 只有归档会话需要目标 Provider 的 thread_id；普通短信跳过一次跨进程查询。
            val thread = resolver.query(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
                arrayOf("thread_id"),
                null,
                null,
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
            if (thread == null || thread <= 0) skipped++
            else mirror.archivedConversationDao().archive(listOf(ArchivedConversationEntity(thread, archivedAt)))
        }
        return skipped
    }

    private data class RestoreRecord(
        val localId: Long,
        val sourceId: Long,
        val address: String?,
        val body: String?,
        val originalDate: Long,
        val dateSent: Long?,
        val threadId: Long?,
    )

    private data class PendingInsert(
        val record: RestoreRecord,
        val type: Int,
        val values: ContentValues,
    )

    private fun open(directory: File, name: String) =
        SQLiteDatabase.openDatabase(File(directory, "databases/$name").path, null, SQLiteDatabase.OPEN_READONLY)

    private fun normalizedType(type: Int): Int = if (type == 4 || type == 6) 5 else type

    private fun messageKey(address: String?, body: String?, date: Long, type: Int): String {
        fun field(value: String?) = if (value == null) "-1:" else "${value.length}:$value"
        return fingerprint("${field(address)}${field(body)}:$date:${normalizedType(type)}")
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RESTORE_BATCH_SIZE = 50
    }
}
