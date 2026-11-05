package vip.mystery0.pixel.text.data.db.mirror

import android.database.Cursor
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.PersonalLearningDatabase
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock
import vip.mystery0.pixel.text.data.source.WhitelistMessageSource
import vip.mystery0.pixel.text.data.source.mirror.SourceRead
import vip.mystery0.pixel.text.data.source.mirror.TelephonyMirrorSource
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.domain.spam.MessageSpamSource
import vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey
import java.util.Locale

/**
 * 将旧数据库中的逐消息记录迁入消息行。仅执行一次，列表后续只读最终标记。
 * 完成标记与目标写入同事务提交；旧表只在提交后删除，中断后可以安全重试。
 */
class MirrorSpamLegacyImporter(
    private val mirror: MessageMirrorDatabase,
    private val legacy: SpamDatabase,
    private val learning: PersonalLearningDatabase,
    private val source: TelephonyMirrorSource,
    private val identities: WhitelistMessageSource,
    private val messages: MessageMirrorRepository,
) {
    private val mutex = Mutex()
    @Volatile private var completed = false

    suspend fun ensureImported() {
        if (completed) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (completed) return@withLock
                if (mirror.syncDao().state(MARKER)?.complete != true) {
                    MirrorSynchronizationLock.mutex.withLock { importLocked() }
                }
                // 目标事务已完成；即使此处进程终止，下次只会补做旧表清理。
                legacy.withTransaction {
                    val db = legacy.openHelper.writableDatabase
                    LEGACY_TABLES.forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
                }
                completed = true
            }
        }
    }

    private suspend fun importLocked() {
        val db = legacy.openHelper.readableDatabase
        val scores = readScores(db)
        val keywords = if (db.hasTable("keyword_spam_match")) {
            db.query("SELECT message_id, keyword_id FROM keyword_spam_match").use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getLong(1)) }
            }
        } else emptyMap()
        val keywordRules = db.query("SELECT id, normalized_keyword FROM blocked_keyword").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) }
        }
        val allowed = if (db.hasTable("spam_allowed_message")) {
            db.query("SELECT message_id, fingerprint FROM spam_allowed_message").use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) }
            }
        } else emptyMap()
        val corrections = learning.openHelper.readableDatabase.query(
            "SELECT l.sourceType, l.sourceId, l.fingerprint, s.label FROM learning_link l JOIN learning_sample s ON s.id=l.sampleId"
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val type = cursor.getString(0)
                    val id = cursor.getLong(1)
                    if (type != "SMS" && type != "MMS") continue
                    val messageId = if (type == "MMS" && id > 0) -id else id
                    val label = cursor.getString(3)
                    if (label == "SPAM" || label == "NORMAL") put(messageId, cursor.getString(2) to (label == "SPAM"))
                }
            }
        }
        val ids = (scores.keys + keywords.keys + allowed.keys + corrections.keys)
            .filter { it != 0L && it != Long.MIN_VALUE }
        val present = mirror.spamDao().rows(ids).mapTo(mutableSetOf()) { it.messageId }
        // 兼容旧安装尚未建立完整镜像的情形：仅补齐待迁移消息，不启动带删除回调的全量同步。
        for (id in ids.filterNot { it in present }) {
            currentCoroutineContext().ensureActive()
            val key = SourceMessageKey(if (id < 0) MessageTransport.MMS else MessageTransport.SMS, kotlin.math.abs(id))
            when (val result = source.readMessage(key)) {
                is SourceRead.Failure -> error("legacy spam import source unavailable category=${result.category}")
                is SourceRead.Success -> result.value?.let { structure ->
                    val generation = mirror.syncDao().state(key.transport.name)?.generation ?: 0
                    mirror.mirrorDao().replaceStructure(structure.copy(message = structure.message.copy(generation = generation)))
                }
            }
        }
        val snapshots = messages.getConversationMessagesByIds(ids).associateBy { it.message.id }
        val allowedIdentities = if (allowed.isEmpty()) emptyMap() else identities.load(allowed.keys).associateBy { it.id }
        val updates = mirror.spamDao().rows(ids).mapNotNull { row ->
            currentCoroutineContext().ensureActive()
            val snapshot = snapshots[row.messageId] ?: return@mapNotNull null
            val message = snapshot.message
            val fingerprint = LearningMessageKey.create(if (message.isMms) "MMS" else "SMS", message.id,
                message.sender, message.timestamp, message.content).fingerprint
            val score = scores[row.messageId]?.takeIf { it.fingerprint == fingerprint }
            val correction = corrections[row.messageId]?.takeIf { it.first == fingerprint }?.second
            row.copy(
                spamScore = score?.score,
                spamModelIdentity = score?.identity,
                spamModelRevision = score?.revision,
                spamModelGeneration = score?.generation,
                spamModelManual = score?.manual ?: false,
                // 旧关键词表没有身份指纹，迁移时依据现存规则和当前正文重新核对。
                spamKeywordId = keywords[row.messageId]?.takeIf { keywordId ->
                    keywordRules[keywordId]?.let { it in message.content.lowercase(Locale.ROOT) } == true
                },
                spamAllowedSource = if (allowed[row.messageId] != null &&
                    allowed[row.messageId] == allowedIdentities[row.messageId]?.fingerprint) MessageSpamSource.WHITELIST else null,
                spamCorrection = correction,
            )
        }
        mirror.withTransaction {
            mirror.spamDao().importDecisions(updates)
            mirror.syncDao().putState(MirrorSyncStateEntity(MARKER, complete = true,
                lastSuccessTime = System.currentTimeMillis(), processedCount = updates.size.toLong()))
        }
        Log.i(TAG, "legacy spam decisions imported count=${updates.size}")
    }

    private fun readScores(db: SupportSQLiteDatabase): Map<Long, LegacyScore> {
        if (!db.hasTable("spam_result")) return emptyMap()
        return db.query("SELECT * FROM spam_result").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val score = cursor.floatOrNull("spam_score") ?: continue
                    if (!score.isFinite() || score !in 0f..1f) continue
                    val fingerprint = cursor.stringOrNull("message_fingerprint") ?: continue
                    val source = cursor.stringOrNull("source").orEmpty()
                    if (source !in setOf("public", "personal", "manual_public", "manual_personal")) continue
                    put(cursor.getLong(cursor.getColumnIndexOrThrow("message_id")), LegacyScore(score, fingerprint,
                        cursor.stringOrNull("model_identity"), cursor.longOrNull("decision_revision"),
                        cursor.longOrNull("personal_generation"), source.startsWith("manual_")))
                }
            }
        }
    }

    private fun SupportSQLiteDatabase.hasTable(name: String): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    private fun Cursor.stringOrNull(name: String): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.longOrNull(name: String): Long? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
    private fun Cursor.floatOrNull(name: String): Float? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getFloat)

    private data class LegacyScore(val score: Float, val fingerprint: String, val identity: String?,
        val revision: Long?, val generation: Long?, val manual: Boolean)

    private companion object {
        const val TAG = "MirrorSpamImport"
        const val MARKER = "spam_decision_import"
        val LEGACY_TABLES = listOf("spam_result", "keyword_spam_match", "spam_allowed_message")
    }
}
