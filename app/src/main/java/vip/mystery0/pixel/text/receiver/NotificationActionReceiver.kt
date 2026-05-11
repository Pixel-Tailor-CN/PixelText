package vip.mystery0.pixel.text.receiver

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 处理通知操作按钮（"已阅"/"删除"）的 BroadcastReceiver。
 *
 * 使用 BroadcastReceiver 而非 Activity，确保在后台也能静默处理，无需唤起 UI。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationActionReceiver"

        /** 将当前会话的所有未读短信标记为已读 */
        const val ACTION_MARK_READ = "vip.mystery0.pixel.text.action.MARK_READ"

        /** 删除本次通知对应的那条短信 */
        const val ACTION_DELETE_SMS = "vip.mystery0.pixel.text.action.DELETE_SMS"

        /** Intent extra：通知 ID，用于 cancel */
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        /** Intent extra：会话 thread_id，标记已读时使用 */
        const val EXTRA_THREAD_ID = "extra_thread_id"

        /** Intent extra：插入数据库后的短信 URI（content://sms/inbox/xxx），删除时使用 */
        const val EXTRA_MESSAGE_URI = "extra_message_uri"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        val messageUriStr = intent.getStringExtra(EXTRA_MESSAGE_URI)

        when (intent.action) {
            ACTION_MARK_READ -> {
                if (threadId != -1L) {
                    markThreadAsRead(context, threadId)
                }
                cancelNotification(context, notificationId)
            }

            ACTION_DELETE_SMS -> {
                if (!messageUriStr.isNullOrBlank()) {
                    deleteSms(context, messageUriStr)
                }
                cancelNotification(context, notificationId)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 内部实现
    // -------------------------------------------------------------------------

    /**
     * 将指定会话中所有未读的收件箱短信标记为已读。
     */
    private fun markThreadAsRead(context: Context, threadId: Long) {
        try {
            val updated = context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.READ, 1) },
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = ?",
                arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
            )
            Log.d(TAG, "Marked $updated messages as read in thread $threadId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark thread $threadId as read", e)
        }
    }

    /**
     * 通过插入时返回的 URI 精准删除单条短信。
     */
    private fun deleteSms(context: Context, messageUriStr: String) {
        try {
            val uri = Uri.parse(messageUriStr)
            val deleted = context.contentResolver.delete(uri, null, null)
            Log.d(TAG, "Deleted $deleted message(s) at $messageUriStr")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete SMS at $messageUriStr", e)
        }
    }

    /**
     * 取消通知。
     */
    private fun cancelNotification(context: Context, notificationId: Int) {
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }
}
