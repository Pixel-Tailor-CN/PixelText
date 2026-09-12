package vip.mystery0.pixel.text.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
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
import org.json.JSONArray
import org.json.JSONObject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.mms.vendor.pdu.PduHeaders
import vip.mystery0.pixel.text.util.SimInfoProvider
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler
import vip.mystery0.pixel.text.worker.MessageMirrorWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 内容下载与确认分别记账，所有本地恢复都先于 SIM 和服务器寿命检查。 */
class MmsDownloadCoordinator(
    private val context: Context,
    private val settings: AppSettingsRepository,
    private val responses: MmsReceptionResponseSender,
) {
    private val requests = MmsReceptionJournal(context, "mms_download_requests")
    private val messageLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private fun mutex(id: Long): Mutex = messageLocks.getOrPut(id) { Mutex() }
    private val root = File(context.filesDir, "mms-download").apply { mkdirs() }
    private val writer = MmsProviderWriter(context.contentResolver)

    suspend fun registerReception(mmsId: Long, source: JSONObject) = mutex(mmsId).withLock {
        val key = mmsId.toString()
        val record = requests.get(key)
        if (record != null) return@withLock
        requests.put(key, JSONObject().put("phase", "idle").put("source", JSONObject(source.toString()))
            .put("mode", source.getString("mode")).put("attempts", 0))
    }

    suspend fun requestDownload(mmsId: Long, userInitiated: Boolean) = withContext(Dispatchers.IO) {
        mutex(mmsId).withLock { request(mmsId, userInitiated) }
    }

    private suspend fun request(mmsId: Long, userInitiated: Boolean) {
        require(mmsId > 0)
        val key = mmsId.toString()
        val previous = requests.get(key)
        when (previous?.optString("phase")) {
            "complete" -> { finishResponse(key, previous); return }
            "parse_failed" -> { if (userInitiated) persist(key, previous); return }
            "persisting", "save_failed" -> {
                if (userInitiated) persist(key, previous)
                return
            }
            "downloading" -> return
            "download_uncertain" -> if (!userInitiated) return
        }
        if (previous != null && !userInitiated && previous.optString("phase") !in setOf("idle", "retry_wait")) return
        val record = previous ?: JSONObject().put("phase", "idle").put("attempts", 0).put("mode", "deferred")
        if (!userInitiated && (record.optString("mode") == "deferred" || !settings.settings.value.autoDownloadMms)) {
            defer(key, record)
            return
        }
        if (!userInitiated && System.currentTimeMillis() < record.optLong("next")) return
        val source = context.contentResolver.query("content://mms/$mmsId".toUri(),
            arrayOf("ct_l", "sub_id", "exp", "m_type", "tr_id"), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null else JSONObject().put("location", cursor.getString(0))
                    .put("sub", cursor.getInt(1)).put("expiry", cursor.getLong(2)).put("type", cursor.getInt(3))
                    .put("transaction", MmsReceptionJournal.encode(cursor.getString(4).orEmpty().toByteArray(Charsets.ISO_8859_1)))
            } ?: run { remove(key, record); return }
        if (source.getInt("type") != 130) return
        // 老版待下载记录仍可下载；老版已完成/本地原件不臆造通知事务。
        if (!record.has("source")) record.put("source", source).put("mode", "deferred")
        val original = record.getJSONObject("source")
        val now = System.currentTimeMillis()
        val expiry = original.optLong("expiry")
        val sub = original.getInt("sub")
        val reason = when {
            original.optString("location").isBlank() -> "missing_location"
            (expiry > 0 || original.has("expiry_value")) && expiry <= now / 1000 -> "expired"
            sub < 0 -> "invalid_subscription"
            SimInfoProvider.getActiveSimList(context).none { it.subscriptionId == sub } -> "sim_unavailable"
            else -> null
        }
        if (reason != null) {
            record.put("phase", reason).put("reason", reason)
            requests.put(key, record)
            if (record.optString("mode") == "auto") defer(key, record, preservePhase = true)
            return
        }
        if (userInitiated) {
            // Deferred 一经落日志，后续设置变化不能重新选择自动 Retrieved。
            record.put("mode", "deferred").put("attempts", 0)
        }
        val token = UUID.randomUUID().toString()
        val target = File(root, "$token.pdu").apply { createNewFile() }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.mms.download", target)
        record.optString("token").takeIf { it.isNotBlank() }?.let { old ->
            // 平台超时未证实完整的文件保留独立身份，不再解析为成功。
            if (record.optString("phase") == "download_uncertain") {
                val retained = record.optJSONArray("uncertain_tokens") ?: JSONArray()
                retained.put(old); record.put("uncertain_tokens", retained)
                revokeFile(old)
            } else removeFile(old)
        }
        record.put("token", token).put("phase", "downloading").put("started", now)
            .put("attempts", record.optInt("attempts") + 1).put("platform_success", false)
            .put("attempt_mode", record.optString("mode", "deferred"))
        record.remove("reason")
        record.remove("response_queued")
        requests.put(key, record)
        val intent = Intent(context, MmsDownloadReceiver::class.java).apply {
            action = MmsDownloadReceiver.ACTION_MMS_DOWNLOADED
            data = "pixeltext://mms-download/$token".toUri()
            putExtra("mms_id", mmsId); putExtra("token", token)
        }
        val callback = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.grantUriPermission("com.android.phone", uri, flags)
            context.grantUriPermission("com.android.mms.service", uri, flags)
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(sub)
                .downloadMultimediaMessage(context, original.getString("location"), uri, null, callback)
            wake(token, REQUEST_TIMEOUT)
            Log.i(TAG, "mms download submitted message_id=$mmsId mode=${record.optString("attempt_mode")} attempt=${record.optInt("attempts")} sub_id=$sub")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(key, record, "submission_failed") }
    }

    suspend fun complete(mmsId: Long, token: String, result: Int, httpStatus: Int) = withContext(Dispatchers.IO) {
        mutex(mmsId).withLock {
            val key = mmsId.toString()
            val record = requests.get(key) ?: return@withLock
            if (record.optString("token") != token || record.optString("phase") !in setOf("downloading", "download_uncertain")) return@withLock
            if (!writer.exists(mmsId)) { remove(key, record); return@withLock }
            record.put("result", result).put("http_status", httpStatus)
            if (result != Activity.RESULT_OK) { fail(key, record, "platform_error"); return@withLock }
            record.put("platform_success", true).put("phase", "persisting")
            requests.put(key, record)
            revokeFile(token)
            Log.i(TAG, "mms download received message_id=$mmsId result=$result")
            MessageMirrorScheduler(context).schedule()
        }
    }

    suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        var retry = false
        requests.entries().forEach { (key, _) ->
            val id = key.toLongOrNull() ?: return@forEach
            mutex(id).withLock {
                val record = requests.get(key) ?: return@withLock
                try {
                    if (!writer.exists(id)) { remove(key, record); return@withLock }
                    when (record.optString("phase")) {
                        "deferred", "expired", "invalid_subscription", "sim_unavailable", "missing_location", "failed", "parse_failed", "download_uncertain" -> {
                            if (record.optString("mode") == "deferred" && !record.optBoolean("deferred_queued")) defer(key, record, preservePhase = true)
                        }
                        "persisting", "save_failed" -> persist(key, record)
                        "complete" -> finishResponse(key, record)
                        "idle", "retry_wait" -> {
                            request(id, false)
                            if (requests.get(key)?.optString("phase") == "retry_wait") retry = true
                        }
                        "downloading" -> if (System.currentTimeMillis() - record.optLong("started") >= REQUEST_TIMEOUT) {
                            // 非空文件也不等于平台完成。保留原件等待迟到回调或用户重新下载。
                            record.put("phase", "download_uncertain").put("reason", "callback_timeout")
                            requests.put(key, record); revokeFile(record.optString("token"))
                            if (record.optString("attempt_mode") == "auto") defer(key, record, preservePhase = true)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { retry = true; Log.w(TAG, "mms download recovery unavailable") }
            }
        }
        retry
    }

    fun state(mmsId: Long): String? = requests.get(mmsId.toString())?.optString("phase")

    private suspend fun persist(key: String, record: JSONObject) {
        val token = record.optString("token")
        if (!validToken(token)) { record.put("phase", "state_invalid"); requests.put(key, record); return }
        try {
            if (!writer.exists(key.toLong())) { remove(key, record); return }
            val file = File(root, "$token.pdu")
            if (file.length() == 0L) { fail(key, record, "empty_pdu"); return }
            if (file.length() > MAX_PDU_BYTES) { retainParseFailure(key, record); return }
            val parsed = try { RetrieveConfParser.parse(file.readBytes()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { retainParseFailure(key, record); return }
            record.put("retrieve_transaction", parsed.transactionId?.let(MmsReceptionJournal::encode) ?: "")
            requests.put(key, record)
            val subscriptionId = record.optJSONObject("source")?.optInt("sub", -1) ?: -1
            val local = if (SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
                MmsAddresses.local(context, subscriptionId)
            } else MmsAddresses.LocalIdentity(emptySet(), "")
            val addresses = buildList {
                parsed.from?.let { add(it.string) }
                parsed.to.orEmpty().forEach { add(it.string) }
                parsed.cc.orEmpty().forEach { add(it.string) }
                parsed.pduHeaders.getEncodedStringValues(PduHeaders.BCC).orEmpty().forEach { add(it.string) }
            }.map(MmsAddresses::clean).filter { it.isNotBlank() && it != "insert-address-token" }
            val (self, participants) = addresses.partition { address ->
                local.numbers.any { MmsAddresses.equivalent(address, it, local.countryIso) }
            }
            // 未识别到本机收件人时保留通知占位的会话，不把可能的自己加入新群聊。
            val thread = participants.toSet().takeIf { self.isNotEmpty() && it.isNotEmpty() }
                ?.let { Telephony.Threads.getOrCreateThreadId(context, it) }
            vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock.mutex.withLock {
                val original = record.optJSONArray("original_addresses")
                val addressIds = if (original == null) emptyList() else (0 until original.length()).mapNotNull {
                    original.optLong(it, -1).takeIf { value -> value > 0 }
                }
                writer.persist(key.toLong(), parsed, addressIds, { ids ->
                    record.put("original_addresses", JSONArray(ids)); requests.put(key, record)
                }, thread)
                // Provider 更新和外部删除不原子，明确验证实际仍存在的完成标记。
                check(isCommitted(key.toLong())) { "mms commit missing" }
            }
            record.put("phase", "complete").put("committed", true)
            record.remove("reason")
            requests.put(key, record)
            revokeFile(token)
            Log.i(TAG, "mms content committed message_id=$key")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!writer.exists(key.toLong())) { remove(key, record); return }
            record.put("phase", "save_failed").put("reason", "provider_write_failed")
            requests.put(key, record)
            throw error
        }
        // 确认队列异常不得把完整内容退回保存失败。
        finishResponse(key, record)
    }

    private fun isCommitted(id: Long): Boolean = context.contentResolver.query("content://mms/$id".toUri(),
        arrayOf("m_type"), null, null, null)?.use { it.moveToFirst() && it.getInt(0) == 132 } == true

    private suspend fun finishResponse(key: String, record: JSONObject) {
        if (record.optBoolean("response_queued")) return
        if (!record.optBoolean("committed") || !record.has("source")) return
        if (!isCommitted(key.toLong())) return
        val source = record.getJSONObject("source")
        if (!source.has("sub") || source.optString("location").isBlank()) {
            record.put("response_reason", "invalid_source").put("response_queued", true)
            requests.put(key, record)
            return
        }
        val auto = record.optString("mode") == "auto" && record.optString("attempt_mode") == "auto"
        val transaction = if (auto) source.optString("transaction") else record.optString("retrieve_transaction")
        if (transaction.isBlank()) {
            record.put("response_reason", "missing_transaction").put("response_queued", true)
        } else {
            responses.enqueue(key.toLong(), source,
                if (auto) MmsReceptionResponseSender.NOTIFY_RESP else MmsReceptionResponseSender.ACKNOWLEDGE,
                if (auto) MmsReceptionResponseSender.RETRIEVED else 0, MmsReceptionJournal.decode(transaction))
            record.put("response_queued", true)
        }
        requests.put(key, record)
    }

    private suspend fun retainParseFailure(key: String, record: JSONObject) {
        record.put("phase", "parse_failed").put("reason", "parser_rejected")
        requests.put(key, record); revokeFile(record.getString("token"))
        if (record.optString("mode") == "auto") defer(key, record, preservePhase = true)
    }

    private suspend fun defer(key: String, record: JSONObject, preservePhase: Boolean = false) {
        record.put("mode", "deferred")
        if (!preservePhase) record.put("phase", "deferred")
        requests.put(key, record)
        val source = record.optJSONObject("source") ?: return
        val transaction = source.optString("transaction")
        if (transaction.isNotBlank()) {
            responses.enqueue(key.toLong(), source, MmsReceptionResponseSender.NOTIFY_RESP,
                MmsReceptionResponseSender.DEFERRED, MmsReceptionJournal.decode(transaction))
            record.put("deferred_queued", true)
            requests.put(key, record)
        }
    }

    private suspend fun fail(key: String, record: JSONObject, reason: String) {
        val autoRetry = record.optString("attempt_mode") == "auto" && record.optString("mode") == "auto" && record.optInt("attempts") < 3
        record.put("phase", if (autoRetry) "retry_wait" else "failed").put("reason", reason)
            .put("next", System.currentTimeMillis() + 30_000L * record.optInt("attempts").coerceAtLeast(1))
        requests.put(key, record)
        removeFile(record.optString("token"))
        if (autoRetry) wake(record.optString("token") + "-retry", 30_000L * record.optInt("attempts").coerceAtLeast(1))
        else if (record.optString("mode") == "auto") defer(key, record, preservePhase = true)
        MessageMirrorScheduler(context).schedule()
    }

    private fun remove(key: String, record: JSONObject) {
        removeFile(record.optString("token"))
        record.optJSONArray("uncertain_tokens")?.let { tokens -> (0 until tokens.length()).forEach { removeFile(tokens.optString(it)) } }
        requests.remove(key)
    }
    private fun validToken(token: String) = runCatching { UUID.fromString(token) }.isSuccess
    private fun removeFile(token: String) { if (validToken(token)) { revokeFile(token); File(root, "$token.pdu").delete() } }
    private fun revokeFile(token: String) {
        if (!validToken(token)) return
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.mms.download", File(root, "$token.pdu"))
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    private fun wake(key: String, delay: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork("mms-timeout-$key", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MessageMirrorWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
    }
    companion object {
        private const val TAG = "MmsDownload"
        private const val REQUEST_TIMEOUT = 15 * 60 * 1000L
        private const val MAX_PDU_BYTES = 64 * 1024 * 1024L
    }
}
