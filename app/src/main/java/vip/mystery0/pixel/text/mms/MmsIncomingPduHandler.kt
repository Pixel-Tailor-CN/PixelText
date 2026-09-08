package vip.mystery0.pixel.text.mms

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler
import vip.mystery0.pixel.text.util.SimInfoProvider

/** 先领取稳定接收身份，再分阶段修复 Provider；所有入口使用同一 Koin 实例。 */
class MmsIncomingPduHandler(
    private val context: Context,
    private val settings: AppSettingsRepository,
    private val downloads: MmsDownloadCoordinator,
    private val notifications: MmsReceptionNotifications,
) {
    private val journal = MmsReceptionJournal(context, "mms_receptions")
    private val mutex = Mutex()
    private val reports = MmsReceptionReports(context)
    private val queue = MmsIncomingPduQueue(context)

    fun reportStates(mmsId: Long): List<MmsReceptionReportState> = reports.states(mmsId)

    suspend fun enqueue(data: ByteArray, subscriptionId: Int, received: Long) = withContext(Dispatchers.IO) {
        if (data.size !in 1..MmsIncomingPduQueue.MAX_PUSH_BYTES) {
            Log.w(TAG, "mms invalid push size")
            return@withContext
        }
        queue.enqueue(MmsIncomingPduQueue.Event(data, subscriptionId, received, settings.settings.value.autoDownloadMms))
        Log.i(TAG, "mms incoming queued bytes=${data.size} sub_id=$subscriptionId")
        // 原事件已经独立落盘；即使调度失败，启动/周期恢复仍能重新发现。
        MessageMirrorScheduler(context).enqueueMetadata()
    }

    private fun consume(eventFile: java.io.File) {
        val queued = try { queue.read(eventFile) }
        catch (_: IllegalArgumentException) {
            queue.quarantine(eventFile)
            Log.w(TAG, "mms incoming quarantined")
            return
        }
        catch (_: java.io.EOFException) {
            queue.quarantine(eventFile)
            Log.w(TAG, "mms incoming quarantined")
            return
        }
        val data = queued.data
        val subscriptionId = queued.sub
        when (val event = WapPushPduParser.parse(data)) {
            is MmsIncomingEvent.Notification -> {
                val value = event.value
                val identity = JSONObject().put("sub", subscriptionId)
                    .put("transaction", MmsReceptionJournal.encode(value.transaction))
                    .put("location", value.contentLocation).toString()
                val key = MmsReceptionJournal.hash(identity.toByteArray())
                if (journal.get(key) == null) run {
                    val now = queued.received
                    JSONObject().put("phase", "claimed").put("received", now)
                        .put("sub", subscriptionId).put("transaction", MmsReceptionJournal.encode(value.transaction))
                        .put("location", value.contentLocation).put("expiry", value.deadline(now))
                        .put("expiry_relative", value.expiryRelative).put("expiry_value", value.expiryValue)
                        .put("version", value.version).put("from", value.from.orEmpty())
                        .put("subject", value.subject.orEmpty()).put("size", value.messageSize)
                        .put("mode", if (queued.auto) "auto" else "deferred")
                        .also { journal.put(key, it) }
                }
            }
            is MmsIncomingEvent.Report -> reports.receive(event, data, subscriptionId)
            is MmsIncomingEvent.Unsupported -> Log.i(TAG, "mms unsupported type=${event.type}")
            MmsIncomingEvent.Invalid -> Log.w(TAG, "mms invalid push")
        }
        // 身份/报告日志已持久化，重复消费仍走逻辑事件去重。
        queue.remove(eventFile)
    }

    suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            var retry = false
            queue.pending().forEach { file ->
                try { consume(file) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { retry = true; Log.w(TAG, "mms incoming consume unavailable") }
            }
            // 插入中断留下的身份优先恢复，不能被后来同 Provider 键的另一个源抢占。
            journal.entries().sortedBy { if (it.second.has("insert_baseline")) 0 else 1 }.forEach { (key, record) ->
                try {
                    if (record.optString("phase") != "deleted") resume(key, record)
                    else if (System.currentTimeMillis() - record.optLong("deleted_at") > TOMBSTONE_LIFETIME &&
                        record.optLong("expiry") < System.currentTimeMillis() / 1000) journal.remove(key)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { retry = true; Log.w(TAG, "mms reception recovery unavailable") }
            }
            reports.recover()
            queue.cleanup()
            retry
        }
    }

    private suspend fun resume(key: String, record: JSONObject) {
        if (record.optString("phase") in setOf("deleted", "state_invalid")) return
        if (!record.has("sub") || !record.has("received") || !record.has("expiry") ||
            record.optString("location").isBlank() || record.optString("transaction").isBlank()) {
            record.put("phase", "state_invalid"); journal.put(key, record); return
        }
        val wasReady = record.optString("phase") == "ready"
        val resolver = context.contentResolver
        var id = record.optLong("mms_id", -1)
        if (id > 0 && !MmsProviderWriter(resolver).exists(id)) {
            record.put("phase", "deleted").put("deleted_at", System.currentTimeMillis())
            // 墓碑只保留稳定摘要，阻止删除后的重推重新创建消息。
            listOf("from", "subject", "transaction", "location").forEach(record::remove)
            journal.put(key, record)
            return
        }
        if (id <= 0) {
            // Provider 对不存在的订阅可能施加外键约束；原始来源仍只保存在独立日志，绝不改用默认卡。
            if (!record.has("provider_sub")) {
                val sourceSub = record.getInt("sub")
                record.put("provider_sub", if (SimInfoProvider.getActiveSimList(context).any { it.subscriptionId == sourceSub }) sourceSub else -1)
                journal.put(key, record)
            }
            val transaction = MmsReceptionJournal.decode(record.getString("transaction")).toString(Charsets.ISO_8859_1)
            val claims = journal.entries().filter { it.first != key }
            check(claims.none { (_, other) -> other.optLong("mms_id", -1) <= 0 && other.has("insert_baseline") &&
                other.optInt("provider_sub", -1) == record.getInt("provider_sub") &&
                other.optString("transaction") == record.getString("transaction") &&
                other.optString("location") == record.getString("location") }) { "mms earlier claim pending" }
            val owned = claims.map { it.second.optLong("mms_id", -1) }.filter { it > 0 }.toSet()
            val candidates = resolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "date", "exp", "sub_id"),
                "tr_id = ? AND ct_l = ? AND sub_id IN (?, ?) AND m_type = 130",
                arrayOf(transaction, record.getString("location"), record.getInt("provider_sub").toString(), record.getInt("sub").toString()), null)
                ?.use { cursor -> buildList<LegacyRow> { while (cursor.moveToNext()) add(LegacyRow(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getInt(3))) } }
                ?: error("mms claim query unavailable")
            val baseline = record.optJSONArray("insert_baseline")
            val beforeInsert = baseline?.let { (0 until it.length()).map(it::getLong).toSet() }
            val available = candidates.filter { it.id !in owned && (beforeInsert == null || it.id !in beforeInsert) }
            // 原卡暂不可用时，仍可认领保存了同一原sub的旧行；降级-1不能证明另一个源的身份。
            val eligible = available.filter {
                beforeInsert != null || it.sub == record.getInt("sub")
            }
            val recovered = eligible.singleOrNull()
            check(eligible.size <= 1 || beforeInsert == null) { "mms ambiguous interrupted insert" }
            if (recovered != null && beforeInsert == null) {
                // 旧130行保留首次接收与截止证据。重推的相对Expiry不能延长原寿命。
                if (recovered.date > 0) record.put("received", minOf(record.getLong("received"), recovered.date * 1000))
                record.put("expiry", minOf(record.getLong("expiry"), recovered.expiry.coerceAtLeast(0)))
                    .put("mode", "deferred").put("legacy_claim", true)
                journal.put(key, record)
            }
            if (recovered == null && beforeInsert == null) {
                record.put("insert_baseline", JSONArray(candidates.map { it.id }))
                journal.put(key, record)
            }
            id = recovered?.id ?: resolver.insert(Telephony.Mms.CONTENT_URI, ContentValues().apply {
                put("date", record.getLong("received") / 1000)
                put("read", 0); put("seen", 0); put("msg_box", 1); put("m_type", 130)
                put("ct_l", record.getString("location")); put("tr_id", transaction)
                put("m_size", record.optLong("size")); put("exp", record.getLong("expiry"))
                put("sub_id", record.getInt("provider_sub")); put("v", record.getInt("version")); put("st", 0)
                put("sub", record.optString("subject").toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1))
                put("sub_cs", 106)
                MmsAddresses.clean(record.optString("from")).takeIf { it.isNotBlank() }?.let {
                    put("thread_id", Telephony.Threads.getOrCreateThreadId(context, setOf(it)))
                }
            })?.lastPathSegment?.toLong() ?: error("mms placeholder insert failed")
            record.put("mms_id", id).put("phase", "address_pending")
            record.remove("insert_baseline")
            journal.put(key, record)
        }
        if (record.optString("phase") != "ready") {
            val sender = MmsAddresses.clean(record.optString("from"))
            if (sender.isNotBlank()) {
                val uri = "content://mms/$id/addr".toUri()
                val exists = resolver.query(uri, arrayOf("_id"), "type = ? AND address = ?",
                    arrayOf("137", sender), null)?.use { it.moveToFirst() } ?: error("mms address query unavailable")
                if (!exists) checkNotNull(resolver.insert(uri, ContentValues().apply {
                    put("address", sender); put("type", 137); put("charset", 106)
                })) { "mms placeholder address unavailable" }
            }
            record.put("phase", "ready")
            journal.put(key, record)
        }
        notifications.received(id, key, record)
        downloads.registerReception(id, record)
        if (!wasReady) MessageMirrorScheduler(context).schedule()
        downloads.requestDownload(id, false)
    }

    companion object {
        private const val TAG = "MmsIncoming"
        private const val TOMBSTONE_LIFETIME = 30L * 24 * 60 * 60 * 1000
    }
    private data class LegacyRow(val id: Long, val date: Long, val expiry: Long, val sub: Int)
}
