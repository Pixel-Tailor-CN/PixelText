package vip.mystery0.pixel.text.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.util.SimInfoProvider
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler
import java.io.File
import java.util.UUID

/** 下载是用户操作，镜像同步器绝不调用此入口。请求日志支持回调时进程重建。 */
class MmsDownloadCoordinator(
    private val context: Context,
    private val settings: AppSettingsRepository,
) {
    private val requests = context.getSharedPreferences("mms_download_requests", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val root = File(context.filesDir, "mms-download").apply { mkdirs() }
    private val writer = MmsProviderWriter(context.contentResolver)

    suspend fun requestDownload(mmsId: Long, userInitiated: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!userInitiated && !settings.settings.value.autoDownloadMms) return@withLock
            check(mmsId > 0) { "无效的彩信" }
            val key = mmsId.toString()
            val previous = requests.getString(key, null)?.let(::JSONObject)
            if (previous?.optString("phase") == "downloading" &&
                System.currentTimeMillis() - previous.optLong("started") < REQUEST_TIMEOUT) return@withLock
            if (previous?.optString("phase") in setOf("persisting", "save_failed")) {
                requireNotNull(previous)
                persist(key, previous)
                return@withLock
            }
            val cursor = context.contentResolver.query(
                "content://mms/$mmsId".toUri(),
                arrayOf("ct_l", "sub_id", "exp", "m_type"), null, null, null,
            ) ?: error("无法读取彩信")
            val source = cursor.use {
                check(it.moveToFirst()) { "彩信已删除" }
                check(it.getInt(3) == 130) { "彩信已经下载" }
                Triple(it.getString(0), it.getInt(1), it.getLong(2))
            }
            check(!source.first.isNullOrBlank()) { "缺少彩信下载地址" }
            check(source.third <= 0 || source.third * 1000 > System.currentTimeMillis()) { "彩信已过期" }
            check(SimInfoProvider.getActiveSimList(context).any { it.subscriptionId == source.second }) {
                "接收彩信的 SIM 卡不可用"
            }
            previous?.optString("token")?.takeIf { it.isNotBlank() }?.let { removeFile(it) }
            val token = UUID.randomUUID().toString()
            val target = File(root, "$token.pdu").apply { createNewFile() }
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.mms.download", target)
            val record = JSONObject().put("token", token).put("phase", "downloading")
                .put("started", System.currentTimeMillis())
            check(requests.edit().putString(key, record.toString()).commit()) { "无法保存下载请求" }
            val intent = Intent(context, MmsDownloadReceiver::class.java).apply {
                action = MmsDownloadReceiver.ACTION_MMS_DOWNLOADED
                data = "pixeltext://mms-download/$token".toUri()
                putExtra("mms_id", mmsId)
                putExtra("token", token)
            }
            val callback = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.grantUriPermission("com.android.phone", uri, flags)
                context.grantUriPermission("com.android.mms.service", uri, flags)
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(source.second)
                    .downloadMultimediaMessage(context, source.first, uri, null, callback)
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    "mms-timeout-$token", androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequestBuilder<vip.mystery0.pixel.text.worker.MessageMirrorWorker>()
                        .setInitialDelay(REQUEST_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
                )
            } catch (error: Exception) {
                fail(key, record)
                throw error
            }
        }
    }

    suspend fun complete(mmsId: Long, token: String, successful: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = mmsId.toString()
            val record = requests.getString(key, null)?.let(::JSONObject) ?: return@withLock
            if (record.optString("token") != token) return@withLock
            if (record.optString("phase") != "downloading") return@withLock
            if (!successful) {
                fail(key, record)
                return@withLock
            }
            record.put("phase", "persisting")
            check(requests.edit().putString(key, record.toString()).commit()) { "download state unavailable" }
            // Receiver 只确认下载结果，耗时解析和持久化交给可恢复的后台任务。
            MessageMirrorScheduler(context).schedule()
        }
    }

    suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            var retry = false
            requests.all.forEach { (key, value) ->
                val record = runCatching { JSONObject(value as String) }.getOrNull() ?: return@forEach
                if (!writer.exists(key.toLong())) {
                    removeFile(record.getString("token"))
                    requests.edit().remove(key).commit()
                } else if (record.optString("phase") in setOf("persisting", "save_failed")) {
                    try { persist(key, record) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { retry = true }
                } else if (record.optString("phase") == "downloading" &&
                    System.currentTimeMillis() - record.optLong("started") >= REQUEST_TIMEOUT) {
                    val downloaded = File(root, "${record.getString("token")}.pdu")
                    if (downloaded.length() in 1..MAX_PDU_BYTES && runCatching {
                            RetrieveConfParser.parse(downloaded.readBytes())
                        }.isSuccess) {
                        record.put("phase", "persisting")
                        requests.edit().putString(key, record.toString()).commit()
                        try { persist(key, record) } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { retry = true }
                    } else fail(key, record)
                }
            }
            retry
        }
    }

    fun state(mmsId: Long): String? = requests.getString(mmsId.toString(), null)
        ?.let { runCatching { JSONObject(it).optString("phase") }.getOrNull() }

    private suspend fun persist(key: String, record: JSONObject) {
        val token = record.getString("token")
        try {
            if (writer.exists(key.toLong())) {
                val file = File(root, "$token.pdu")
                // 限制解析器内存上界，过大内容明确失败，不能截断为成功消息。
                if (file.length() !in 1..MAX_PDU_BYTES) {
                    fail(key, record)
                    return
                }
                val bytes = file.readBytes()
                val parsed = try { RetrieveConfParser.parse(bytes) }
                catch (_: IllegalArgumentException) { fail(key, record); return }
                catch (_: IllegalStateException) { fail(key, record); return }
                vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock.mutex.withLock {
                    val original = record.optJSONArray("original_addresses")
                    val addressIds = if (original == null) emptyList() else (0 until original.length()).map(original::getLong)
                    writer.persist(key.toLong(), parsed, addressIds) { addresses ->
                        record.put("original_addresses", org.json.JSONArray(addresses))
                        check(requests.edit().putString(key, record.toString()).commit()) { "mms address journal unavailable" }
                    }
                }
            }
            record.put("phase", "complete")
            requests.edit().putString(key, record.toString()).commit()
            // 保留原始 PDU，避免未知头信息丢失；源消息删除后 recover 同步清理。
            revokeFile(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 源写入失败仍保留已下载 PDU，下一次后台任务从日志恢复。
            record.put("phase", "save_failed")
            requests.edit().putString(key, record.toString()).commit()
            throw error
        }
    }

    private fun fail(key: String, record: JSONObject) {
        record.put("phase", "failed")
        requests.edit().putString(key, record.toString()).commit()
        removeFile(record.getString("token"))
        MessageMirrorScheduler(context).schedule()
    }

    private fun removeFile(token: String) {
        if (!runCatching { UUID.fromString(token) }.isSuccess) return
        val file = File(root, "$token.pdu")
        revokeFile(token)
        file.delete()
    }

    private fun revokeFile(token: String) {
        val file = File(root, "$token.pdu")
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.mms.download", file)
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    companion object {
        private const val REQUEST_TIMEOUT = 15 * 60 * 1000L
        private const val MAX_PDU_BYTES = 64 * 1024 * 1024L
    }
}
