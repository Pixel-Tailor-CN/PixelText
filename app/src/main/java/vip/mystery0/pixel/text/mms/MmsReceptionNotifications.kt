package vip.mystery0.pixel.text.mms

import android.app.NotificationManager
import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.repository.MmsContentRepository
import vip.mystery0.pixel.text.notification.SmsNotificationHelper
import vip.mystery0.pixel.text.data.repository.SenderProfileRepository
import vip.mystery0.pixel.text.data.source.ContactDataSource

/** 独立稳定通知 ID；重复接收/正文更新静音，已读或用户划掉后不重新弹出。 */
class MmsReceptionNotifications(
    private val context: Context,
    private val content: MmsContentRepository,
    private val profiles: SenderProfileRepository,
    private val contacts: ContactDataSource,
) {
    private val journal = MmsReceptionJournal(context, "mms_reception_notifications")
    suspend fun received(id: Long, identity: String, reception: JSONObject) {
        if (journal.get(id.toString()) != null) return
        val row = source(id) ?: return
        val record = JSONObject().put("identity", identity).put("sender", MmsAddresses.clean(reception.optString("from")))
            .put("phase", "attempted").put("summary", reception.optString("subject").ifBlank { "收到一条彩信" })
        // 先标记尝试，进程中断后至多静默更新，避免重复响铃。
        journal.put(id.toString(), record)
        if (!row.read) show(id, row.thread, record, silent = false)
    }

    suspend fun refresh() {
        journal.entries().forEach { (key, record) ->
            val id = key.toLongOrNull() ?: return@forEach
            try {
                val row = source(id)
                val manager = context.getSystemService(NotificationManager::class.java)
                if (row == null) { manager.cancel(notificationId(id)); journal.remove(key); return@forEach }
                if (row.read) { manager.cancel(notificationId(id)); return@forEach }
                if (row.type != 132 || manager.activeNotifications.none { it.id == notificationId(id) }) return@forEach
                val model = withTimeoutOrNull(1500) {
                    content.observe(SourceMessageKey(MessageTransport.MMS, id)).first { it == null || (!it.preparing && !it.pendingDownload) }
                } ?: return@forEach
                if (record.optString("summary") == model.summary && record.optLong("thread") == row.thread) return@forEach
                record.put("summary", model.summary).put("thread", row.thread).put("phase", "content")
                // 附件 READY/hash 变化会发新模型，后续附件任务继续刷新；不拿此信号发送协议确认。
                journal.put(key, record)
                show(id, row.thread, record, silent = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.w("MmsNotification", "mms notification refresh unavailable") }
        }
    }

    private suspend fun show(id: Long, thread: Long, record: JSONObject, silent: Boolean) {
        val sender = record.optString("sender").ifBlank { "未知发件人" }
        val profile = try { profiles.findByNumber(sender) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val contact = runCatching { contacts.getDisplayName(sender) }.getOrNull()
        SmsNotificationHelper.showSmsNotification(context, sender,
            record.getString("summary"), threadId = thread, messageUri = "content://mms/$id",
            displaySender = contact ?: profile?.displayName ?: sender, avatarPath = profile?.avatarPath,
            notificationIdOverride = notificationId(id), silent = silent)
    }
    private data class Source(val thread: Long, val read: Boolean, val type: Int)
    private fun source(id: Long): Source? = context.contentResolver.query("content://mms/$id".toUri(),
        arrayOf("thread_id", "read", "m_type"), null, null, null)?.use {
        if (it.moveToFirst()) Source(it.getLong(0), it.getInt(1) != 0, it.getInt(2)) else null
    }
    private fun notificationId(id: Long): Int = -1_000_000 - id.toInt()
}
