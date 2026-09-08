package vip.mystery0.pixel.text.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.mms.vendor.pdu.PduParser
import vip.mystery0.pixel.text.mms.vendor.pdu.PduHeaders
import vip.mystery0.pixel.text.util.SimInfoProvider
import vip.mystery0.pixel.text.worker.MessageMirrorWorker
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 确认状态独立于正文。未知网络结果只能重放同字节，不能承诺跨网络恰好一次。 */
class MmsReceptionResponseSender(private val context: Context) {
    private val journal = MmsReceptionJournal(context, "mms_reception_responses")
    private val mutex = Mutex()
    private val root = File(context.filesDir, "mms-download").apply { mkdirs() }
    private val writer = MmsProviderWriter(context.contentResolver)

    fun state(mmsId: Long): List<MmsReceptionResponseState> = journal.entries()
        .map { it.second }.filter { it.optLong("mms_id") == mmsId }.map {
            MmsReceptionResponseState(it.optInt("type"), it.optInt("status"), it.optString("phase"),
                it.optInt("attempts"), it.optString("reason").takeIf(String::isNotBlank))
        }

    suspend fun enqueue(mmsId: Long, source: JSONObject, type: Int, status: Int, transaction: ByteArray) = mutex.withLock {
        if (!writer.exists(mmsId)) return@withLock
        if (transaction.isEmpty()) {
            Log.w(TAG, "mms response unavailable message_id=$mmsId reason=missing_transaction")
            return@withLock
        }
        val encoded = encode(type, status, transaction)
        val sub = source.getInt("sub")
        val key = MmsReceptionJournal.hash("$mmsId:$sub:$type:$status:".toByteArray() + transaction)
        if (journal.get(key) != null) return@withLock
        if (status != DEFERRED) {
            // 尚未交给平台的旧延期响应失去意义；已发送或在途响应不能伪装撤回。
            journal.entries().filter { it.second.optLong("mms_id") == mmsId &&
                it.second.optInt("status") == DEFERRED && it.second.optString("phase") in setOf("pending", "retry_wait")
            }.forEach { (oldKey, old) -> old.put("phase", "cancelled"); journal.put(oldKey, old); cleanup(old) }
        }
        val now = System.currentTimeMillis()
        val useLocation = sub >= 0 && context.getSystemService(SmsManager::class.java).createForSubscriptionId(sub)
            .carrierConfigValues.getBoolean(SmsManager.MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED)
        journal.put(key, JSONObject().put("mms_id", mmsId).put("sub", sub).put("type", type).put("status", status)
            .put("pdu", MmsReceptionJournal.encode(encoded)).put("hash", MmsReceptionJournal.hash(encoded))
            .put("location", if (useLocation) source.getString("location") else JSONObject.NULL)
            .put("location_policy", if (useLocation) "notification" else "apn")
            .put("created", now).put("deadline", now + RESPONSE_LIFETIME)
            .put("phase", "pending").put("attempts", 0).put("next", now))
        schedule(0)
    }

    suspend fun complete(key: String, token: String, result: Int, httpStatus: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = journal.get(key) ?: return@withLock
            if (record.optString("token") != token || record.optString("phase") != "sending") return@withLock
            record.put("result", result).put("http_status", httpStatus)
            if (result == Activity.RESULT_OK) {
                record.put("phase", "sent")
                record.remove("reason")
            }
            else retry(record, "platform_error")
            journal.put(key, record)
            cleanup(record)
            Log.i(TAG, "mms response result message_id=${record.optLong("mms_id")} type=${record.optInt("type")} status=${record.optInt("status")} phase=${record.optString("phase")} result=$result")
            schedule(0)
        }
    }

    suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            var more = false
            val busySources = mutableSetOf<Long>()
            val records = journal.entries()
            records.sortedBy { it.second.optLong("created") }.forEach { (key, record) ->
                try {
                    val id = record.optLong("mms_id", -1)
                    if (id <= 0) {
                        record.put("phase", "terminal_failed").put("reason", "state_invalid")
                        journal.put(key, record); cleanup(record); return@forEach
                    }
                    if (!writer.exists(id)) {
                        cleanup(record); journal.remove(key); return@forEach
                    }
                    var phase = record.optString("phase")
                    if (phase in TERMINAL) { cleanup(record); return@forEach }
                    if (!record.has("sub") || !record.has("deadline") || record.optString("pdu").isBlank()) {
                        record.put("phase", "terminal_failed").put("reason", "state_invalid")
                        journal.put(key, record); cleanup(record); return@forEach
                    }
                    more = true
                    if (phase == "sending") {
                        if (now - record.optLong("started") < TIMEOUT) { busySources.add(id); return@forEach }
                        retry(record, "callback_timeout"); journal.put(key, record); cleanup(record)
                        phase = record.optString("phase")
                    }
                    if (now >= record.getLong("deadline") || record.optInt("attempts") >= MAX_ATTEMPTS) {
                        record.put("phase", "terminal_failed").put("reason", "retry_exhausted")
                        journal.put(key, record); cleanup(record); return@forEach
                    }
                    if (phase !in setOf("pending", "retry_wait") || id in busySources || now < record.optLong("next")) return@forEach
                    val sub = record.getInt("sub")
                    if (sub < 0 || SimInfoProvider.getActiveSimList(context).none { it.subscriptionId == sub }) {
                        record.put("phase", "retry_wait").put("reason", if (sub < 0) "invalid_subscription" else "sim_unavailable")
                            .put("next", now + 5 * 60_000)
                        journal.put(key, record); schedule(5 * 60_000); return@forEach
                    }
                    val token = UUID.randomUUID().toString()
                    val bytes = runCatching { MmsReceptionJournal.decode(record.getString("pdu")) }.getOrNull()
                    if (bytes == null || MmsReceptionJournal.hash(bytes) != record.optString("hash")) {
                        record.put("phase", "terminal_failed").put("reason", "state_invalid")
                        journal.put(key, record); cleanup(record); return@forEach
                    }
                    val file = File(root, "response-$token.pdu")
                    file.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
                    record.put("token", token).put("phase", "sending").put("started", now)
                        .put("attempts", record.optInt("attempts") + 1)
                    journal.put(key, record)
                    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.mms.download", file)
                    val intent = Intent(context, MmsReceptionResponseReceiver::class.java).apply {
                        action = MmsReceptionResponseReceiver.ACTION
                        data = "pixeltext://mms-response/$token".toUri()
                        putExtra("key", key); putExtra("token", token)
                    }
                    val callback = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    try {
                        if (!writer.exists(id)) {
                            record.put("phase", "cancelled").put("reason", "source_deleted")
                            journal.put(key, record); cleanup(record); return@forEach
                        }
                        context.grantUriPermission("com.android.phone", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.grantUriPermission("com.android.mms.service", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.getSystemService(SmsManager::class.java).createForSubscriptionId(sub)
                            .sendMultimediaMessage(context, uri, if (record.isNull("location")) null else record.getString("location"), null, callback)
                        busySources.add(id)
                        schedule(TIMEOUT)
                        Log.i(TAG, "mms response submitted message_id=$id type=${record.optInt("type")} status=${record.optInt("status")} attempt=${record.optInt("attempts")} sub_id=$sub")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        retry(record, "submission_failed"); journal.put(key, record); cleanup(record)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { more = true; Log.w(TAG, "mms response recovery unavailable") }
            }
            // 仅清理本队列确定属于确认的孤立文件；正文原件不按文件年龄推断可删除。
            val activeTokens = journal.entries().filter { it.second.optString("phase") == "sending" }
                .map { it.second.optString("token") }.toSet()
            root.listFiles()?.filter { it.name.startsWith("response-") && it.extension == "pdu" &&
                it.name.removePrefix("response-").removeSuffix(".pdu") !in activeTokens && now - it.lastModified() > TIMEOUT
            }?.forEach { file -> file.delete() }
            more
        }
    }

    private fun retry(record: JSONObject, reason: String) {
        record.put("phase", "retry_wait").put("reason", reason)
            .put("next", System.currentTimeMillis() + (30_000L shl record.optInt("attempts").coerceIn(0, 6)))
        schedule((record.optLong("next") - System.currentTimeMillis()).coerceAtLeast(0))
    }

    private fun cleanup(record: JSONObject) {
        val token = record.optString("token")
        if (runCatching { UUID.fromString(token) }.isFailure) return
        val file = File(root, "response-$token.pdu")
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.mms.download", file)
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        file.delete()
    }

    private fun schedule(delay: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork("mms-response-wake-${System.currentTimeMillis() / 1000}-${delay}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MessageMirrorWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
    }

    companion object {
        const val NOTIFY_RESP = 131
        const val ACKNOWLEDGE = 133
        const val DEFERRED = 131
        const val RETRIEVED = 129
        private const val TAG = "MmsResponse"
        private const val TIMEOUT = 15 * 60_000L
        private const val RESPONSE_LIFETIME = 24 * 60 * 60_000L
        private const val MAX_ATTEMPTS = 5
        private val TERMINAL = setOf("sent", "terminal_failed", "cancelled")

        /** 仅接收协议的两种无正文 PDU，保留事务原字节及 Text-string Quote。 */
        internal fun encode(type: Int, status: Int, transaction: ByteArray): ByteArray {
            require(type == NOTIFY_RESP || type == ACKNOWLEDGE)
            require(transaction.isNotEmpty() && transaction.none { it == 0.toByte() })
            val bytes = ByteArrayOutputStream().apply {
                write(0x8c); write(type); write(0x98)
                if ((transaction[0].toInt() and 0xff) >= 0x80) write(0x7f)
                write(transaction); write(0); write(0x8d); write(0x92)
                if (type == NOTIFY_RESP) { require(status == DEFERRED || status == RETRIEVED); write(0x95); write(status) }
            }.toByteArray()
            synchronized(RetrieveConfParser) {
                val parsed = requireNotNull(PduParser(bytes, false).parse())
                check(parsed.messageType == type && parsed.mmsVersion == 0x12)
                check(parsed.pduHeaders.getTextString(PduHeaders.TRANSACTION_ID).contentEquals(transaction))
                if (type == NOTIFY_RESP) check(parsed.pduHeaders.getOctet(PduHeaders.STATUS) == status)
            }
            return bytes
        }
    }
}
