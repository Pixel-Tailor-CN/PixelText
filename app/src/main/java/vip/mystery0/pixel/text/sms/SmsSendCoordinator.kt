package vip.mystery0.pixel.text.sms

import androidx.annotation.StringRes
import androidx.core.net.toUri
import android.app.Activity
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.AtomicFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.repository.ConversationCacheRepository
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.util.AppStrings
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 发送只调用一次；恢复任务仅更新状态，绝不重新调用 SmsManager。日志不保存正文。 */
class SmsSendCoordinator(
    private val context: Context,
    private val source: TelephonyDataSource,
    private val cache: ConversationCacheRepository,
) {
    enum class State(@param:StringRes val labelRes: Int) {
        SENDING(R.string.sys_sms_state_sending),
        SENT(R.string.sys_sms_state_sent),
        FAILED(R.string.sys_sms_state_failed),
        UNKNOWN(R.string.sys_sms_state_unknown);

        val label: String
            get() = AppStrings.get(labelRes)
    }

    private val lock = Mutex()
    private val directory get() = File(context.noBackupFilesDir, "sms-send-journal").also { it.mkdirs() }
    private val mutableStates = MutableStateFlow<Map<Long, State>>(emptyMap())
    val states = mutableStates.asStateFlow()

    suspend fun send(address: String, body: String, subId: Int, threadId: Long = -1L, draftKey: String? = null): Long =
        withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                // 接纳后进程死亡或草稿清理失败，再次提交同一版本只返回原记录，不重复调用平台。
                if (draftKey != null) {
                    val accepted = directory.listFiles().orEmpty().asSequence().filter { validToken(it.name) }
                        .mapNotNull { read(it.name) }.firstOrNull { it.optString("draftKey") == draftKey }
                    if (accepted != null) return@withLock accepted.getLong("id")
                }
                check(context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)) {
                    context.getString(R.string.sys_sms_set_default_app)
                }
                require(address.isNotBlank() && body.isNotBlank()) { context.getString(R.string.sys_sms_empty_address_or_body) }
                val base = context.getSystemService(SmsManager::class.java)
                val manager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base
                val parts = manager.divideMessage(body)
                require(parts.isNotEmpty()) { context.getString(R.string.sys_sms_invalid_body) }
                val uri = requireNotNull(source.insertOutboxPlaceholder(address, body, threadId, subId)) { context.getString(R.string.sys_sms_save_outbox_failed) }
                val id = requireNotNull(uri.lastPathSegment?.toLongOrNull())
                val token = UUID.randomUUID().toString()
                val record = JSONObject().put("id", id).put("uri", uri.toString())
                    .put("thread", source.queryThreadIdFromUri(uri) ?: threadId)
                    .put("identity", identity(uri) ?: error(context.getString(R.string.sys_sms_outbox_unavailable)))
                    .put("started", System.currentTimeMillis()).put("count", parts.size)
                    .put("draftKey", draftKey)
                    .put("state", State.SENDING.name).put("results", JSONObject())
                try {
                    save(token, record)
                    // 在平台接纳前持久安排超时检查；进程重建也会再次扫描日志。
                    scheduleRecovery(TIMEOUT_MILLIS, token)
                } catch (error: Exception) {
                    record.put("state", State.FAILED.name)
                    runCatching { save(token, record) }
                    source.updateSmsSendResult(uri, SmsManager.RESULT_ERROR_GENERIC_FAILURE, false)
                    throw error
                }
                publish(record)
                val callbacks = ArrayList(parts.indices.map { index ->
                    PendingIntent.getBroadcast(context, 0,
                        Intent(context, SmsSentReceiver::class.java).apply {
                            data = "pixeltext://sms-sent/$token/$index".toUri()
                        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                })
                try {
                    if (parts.size == 1) manager.sendTextMessage(address, null, body, callbacks[0], null)
                    else manager.sendMultipartTextMessage(address, null, parts, callbacks, null)
                } catch (error: Exception) {
                    // 参数和权限拒绝是确定失败；其它提交异常不能证明平台没有接纳。
                    record.put("state", if (error is SecurityException || error is IllegalArgumentException) State.FAILED.name else State.UNKNOWN.name)
                    save(token, record)
                    publish(record)
                }
                runCatching { syncProvider(record) }
                runCatching { cache.syncLocalChanges() }
                id
            }
        }

    suspend fun complete(token: String, index: Int, resultCode: Int) = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            if (!validToken(token)) return@withLock
            val record = read(token) ?: return@withLock
            if (index !in 0 until record.getInt("count")) return@withLock
            val results = record.getJSONObject("results")
            if (!results.has(index.toString())) results.put(index.toString(), resultCode)
            val failed = results.keys().asSequence().any { results.getInt(it) != Activity.RESULT_OK }
            val state = when {
                failed -> State.FAILED
                results.length() == record.getInt("count") -> State.SENT
                else -> State.valueOf(record.getString("state"))
            }
            record.put("state", state.name)
            // 先落回执，再写 Provider；角色丢失或写入失败可重试，但不重发短信。
            save(token, record)
            publish(record)
            runCatching {
                syncProvider(record)
                if (state in setOf(State.SENT, State.FAILED)) {
                    WorkManager.getInstance(context).cancelUniqueWork("sms-send-recovery-$token")
                }
            }.onFailure { scheduleRecovery(30_000, token) }
            runCatching { cache.syncLocalChanges() }
        }
    }

    suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            var retry = false
            val now = System.currentTimeMillis()
            val allStates = mutableMapOf<Long, State>()
            directory.listFiles().orEmpty().filter { validToken(it.name) }.forEach { file ->
                val record = read(file.name) ?: return@forEach
                try {
                    val uri = Uri.parse(record.getString("uri"))
                    if (identity(uri) != record.getString("identity")) {
                        AtomicFile(file).delete()
                        return@forEach
                    }
                    if (record.getString("state") == State.SENDING.name) {
                        val remaining = TIMEOUT_MILLIS - (now - record.getLong("started"))
                        if (remaining <= 0) {
                            record.put("state", State.UNKNOWN.name)
                            save(file.name, record)
                        } else scheduleRecovery(remaining, file.name)
                    }
                    syncProvider(record)
                    val state = State.valueOf(record.getString("state"))
                    allStates[record.getLong("id")] = state
                    // 确定终态已写回系统，七天后无需继续保存回执去重日志。
                    if (state in setOf(State.SENT, State.FAILED) && now - record.getLong("started") > TimeUnit.DAYS.toMillis(7)) {
                        AtomicFile(file).delete()
                    }
                } catch (_: Exception) {
                    retry = true
                    allStates[record.getLong("id")] = State.valueOf(record.getString("state"))
                }
            }
            mutableStates.value = allStates
            retry
        }
    }

    fun scheduleRecovery(delay: Long = 0, token: String = "launch") {
        WorkManager.getInstance(context).enqueueUniqueWork("sms-send-recovery-$token", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SmsSendRecoveryWorker>().setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS).build())
    }

    private fun syncProvider(record: JSONObject) {
        val state = State.valueOf(record.getString("state"))
        if (state !in setOf(State.SENT, State.FAILED)) return
        val uri = Uri.parse(record.getString("uri"))
        if (identity(uri) != record.getString("identity")) return
        val type = if (state == State.SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        val currentType = context.contentResolver.query(uri, arrayOf("type"), null, null, null)?.use { if (it.moveToFirst()) it.getInt(0) else null }
        if (currentType == type) return
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, type)
            if (state == State.SENT) put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            else put(Telephony.Sms.ERROR_CODE, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        }
        check(context.contentResolver.update(uri, values, null, null) == 1) { "sms result write unavailable" }
    }

    private fun identity(uri: Uri): String? = context.contentResolver.query(uri, arrayOf("address", "date", "body"), null, null, null)?.use {
        if (!it.moveToFirst()) null else MessageDigest.getInstance("SHA-256")
            .digest("${it.getString(0)}\u0000${it.getLong(1)}\u0000${it.getString(2)}".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun publish(record: JSONObject) {
        mutableStates.value += record.getLong("id") to State.valueOf(record.getString("state"))
    }

    private fun read(token: String): JSONObject? = runCatching {
        JSONObject(AtomicFile(File(directory, token)).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()

    private fun save(token: String, record: JSONObject) {
        val atomic = AtomicFile(File(directory, token))
        val stream = atomic.startWrite()
        try {
            stream.write(record.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun validToken(token: String) = runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)
    private companion object { const val TIMEOUT_MILLIS = 15 * 60 * 1000L }
}
