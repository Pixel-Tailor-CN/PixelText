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

    fun reportStates(mmsId: Long): List<MmsReceptionReportState> = reports.states(mmsId)

    suspend fun handleIncoming(data: ByteArray, subscriptionId: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val event = WapPushPduParser.parse(data)) {
                is MmsIncomingEvent.Notification -> {
                    val value = event.value
                    val identity = JSONObject().put("sub", subscriptionId)
                        .put("transaction", MmsReceptionJournal.encode(value.transaction))
                        .put("location", value.contentLocation).toString()
                    val key = MmsReceptionJournal.hash(identity.toByteArray())
                    val record = journal.get(key) ?: run {
                        val now = System.currentTimeMillis()
                        JSONObject().put("phase", "claimed").put("received", now)
                            .put("sub", subscriptionId).put("transaction", MmsReceptionJournal.encode(value.transaction))
                            .put("location", value.contentLocation).put("expiry", value.deadline(now))
                            .put("expiry_relative", value.expiryRelative).put("expiry_value", value.expiryValue)
                            .put("version", value.version).put("from", value.from.orEmpty())
                            .put("subject", value.subject.orEmpty()).put("size", value.messageSize)
                            .put("mode", if (settings.settings.value.autoDownloadMms) "auto" else "deferred")
                            .also { journal.put(key, it) }
                    }
                    resume(key, record)
                }
                is MmsIncomingEvent.Report -> reports.receive(event, data, subscriptionId)
                is MmsIncomingEvent.Unsupported -> Log.i(TAG, "mms unsupported type=${event.type}")
                MmsIncomingEvent.Invalid -> Log.w(TAG, "mms invalid push")
            }
        }
    }

    suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            var retry = false
            journal.entries().forEach { (key, record) ->
                try {
                    if (record.optString("phase") != "deleted") resume(key, record)
                    else if (System.currentTimeMillis() - record.optLong("deleted_at") > TOMBSTONE_LIFETIME &&
                        record.optLong("expiry") < System.currentTimeMillis() / 1000) journal.remove(key)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { retry = true; Log.w(TAG, "mms reception recovery unavailable") }
            }
            reports.recover()
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
            val ids = resolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"),
                "tr_id = ? AND ct_l = ? AND sub_id = ?",
                arrayOf(transaction, record.getString("location"), record.getInt("provider_sub").toString()), null)
                ?.use { cursor -> buildList<Long> { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
                ?: error("mms claim query unavailable")
            check(ids.size <= 1) { "mms ambiguous legacy notification" }
            id = ids.singleOrNull() ?: resolver.insert(Telephony.Mms.CONTENT_URI, ContentValues().apply {
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
}
