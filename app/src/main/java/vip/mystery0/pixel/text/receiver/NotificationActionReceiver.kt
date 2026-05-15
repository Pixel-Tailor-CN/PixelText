package vip.mystery0.pixel.text.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.notification.SmsNotificationHelper

/**
 * 处理通知操作按钮（"已阅" / "回复"）的 BroadcastReceiver。
 *
 * 使用 BroadcastReceiver 而非 Activity，确保在后台也能静默处理，无需唤起 UI。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationActionReceiver"

        /** 将当前会话的所有未读短信标记为已读 */
        const val ACTION_MARK_READ = "vip.mystery0.pixel.text.action.MARK_READ"

        /** 直接从通知栏回复短信（RemoteInput inline reply） */
        const val ACTION_REPLY_SMS = "vip.mystery0.pixel.text.action.REPLY_SMS"

        /** 复制验证码，并将对应短信标记为已读 */
        const val ACTION_COPY_VERIFICATION_CODE =
            "vip.mystery0.pixel.text.action.COPY_VERIFICATION_CODE"

        /** Intent extra：通知 ID，用于 cancel / update */
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        /** Intent extra：会话 thread_id，标记已读时使用 */
        const val EXTRA_THREAD_ID = "extra_thread_id"

        /** Intent extra：短信数据库 URI，优先用于精确标记单条短信已读 */
        const val EXTRA_MESSAGE_URI = "extra_message_uri"

        /** Intent extra：回复目标的手机号 / 发件人地址 */
        const val EXTRA_REPLY_ADDRESS = "extra_reply_address"

        /** Intent extra：待复制的验证码 */
        const val EXTRA_VERIFICATION_CODE = "extra_verification_code"

        /**
         * RemoteInput result key：从通知栏输入框取出回复文本时使用的 key。
         * 必须与 [SmsNotificationHelper] 中 RemoteInput.Builder 的 key 一致。
         */
        const val EXTRA_REPLY_TEXT = "extra_reply_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)

        when (intent.action) {
            ACTION_MARK_READ -> {
                if (threadId != -1L) markThreadAsRead(context, threadId)
                cancelNotification(context, notificationId)
            }

            ACTION_COPY_VERIFICATION_CODE -> {
                val code = intent.getStringExtra(EXTRA_VERIFICATION_CODE)
                val messageUri = intent.getStringExtra(EXTRA_MESSAGE_URI)
                if (!code.isNullOrBlank()) {
                    copyVerificationCode(context, code)
                    markMessageAsRead(context, messageUri, threadId)
                } else {
                    Log.w(TAG, "copy verification skipped: code is blank")
                }
                cancelNotification(context, notificationId)
            }

            ACTION_REPLY_SMS -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(EXTRA_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                val address = intent.getStringExtra(EXTRA_REPLY_ADDRESS)

                if (!replyText.isNullOrBlank() && !address.isNullOrBlank()) {
                    val sent = sendSmsReply(context, address, replyText)
                    // 发送后必须更新通知，否则系统会一直显示转圈进度条
                    updateNotificationAfterReply(context, notificationId, sent)
                } else {
                    Log.w(TAG, "reply skipped: replyText=$replyText, address=$address")
                    cancelNotification(context, notificationId)
                }
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
                readValues(),
                "${Telephony.Sms.THREAD_ID} = ? AND (${Telephony.Sms.READ} = 0 OR ${Telephony.Sms.SEEN} = 0) AND ${Telephony.Sms.TYPE} = ?",
                arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to mark thread $threadId as read", e)
        }
    }

    /**
     * 优先将通知对应的单条短信标记为已读；URI 不可用时回退到会话级已读。
     */
    private fun markMessageAsRead(context: Context, messageUri: String?, threadId: Long) {
        if (!messageUri.isNullOrBlank()) {
            try {
                val updated = context.contentResolver.update(
                    Uri.parse(messageUri),
                    readValues(),
                    null,
                    null
                )
                if (updated > 0) return
            } catch (e: Exception) {
                Log.e(TAG, "failed to mark message as read", e)
            }
        }

        if (threadId != -1L) {
            markThreadAsRead(context, threadId)
        }
    }

    private fun readValues(): ContentValues {
        return ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
    }

    /**
     * 将验证码写入系统剪贴板。
     */
    private fun copyVerificationCode(context: Context, code: String) {
        try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(
                ClipData.newPlainText("verification code", code)
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to copy verification code", e)
        }
    }

    /**
     * 使用 [SmsManager] 发送回复短信，自动处理超过 160 字符的长短信分段。
     *
     * 发送成功后，**必须**将该条消息写入系统 SMS 数据库的已发送目录，
     * 否则 Google Messages 等任何短信应用都看不到这条发出的记录。
     *
     * @return true 表示调用 sendTextMessage / sendMultipartTextMessage 未抛异常
     */
    private fun sendSmsReply(context: Context, recipient: String, text: String): Boolean {
        return try {
            val smsManager: SmsManager = context.getSystemService(SmsManager::class.java)

            val parts = smsManager.divideMessage(text)
            if (parts.size == 1) {
                smsManager.sendTextMessage(recipient, null, text, null, null)
            } else {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            }

            // 写入系统数据库，使所有短信应用均可看到该发送记录
            saveSentMessageToDb(context, recipient, text)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to send reply to $recipient", e)
            false
        }
    }

    /**
     * 将已发出的短信写入系统 SMS 数据库的 Sent 目录（content://sms/sent）。
     *
     * 作为默认短信应用，[SmsManager] 只负责发送无线信号，数据库持久化由应用自行负责。
     * 不写入数据库会导致：其他短信应用看不到、本应用重启后记录丢失、对话 thread_id 无法正确关联。
     */
    private fun saveSentMessageToDb(context: Context, recipient: String, text: String) {
        try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, recipient)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.DATE_SENT, now)
                // 已发送消息默认标记为"已读"
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "failed to save sent message to DB", e)
        }
    }

    /**
     * 回复后将通知更新为"已发送"提示，并立即设置 autoCancel。
     *
     * 必须更新（不能只 cancel）：RemoteInput 提交后系统会等待宿主通知被更新，
     * 否则通知栏会持续显示转圈的进度指示器。
     */
    private fun updateNotificationAfterReply(
        context: Context,
        notificationId: Int,
        success: Boolean,
    ) {
        if (notificationId == -1) return

        // Android 13+ 检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                cancelNotification(context, notificationId)
                return
            }
        }

        val statusText = if (success) {
            context.getString(R.string.notification_reply_sent)
        } else {
            context.getString(R.string.notification_reply_failed)
        }

        val updatedNotification =
            NotificationCompat.Builder(context, SmsNotificationHelper.CHANNEL_ID_SMS)
                .setSmallIcon(R.drawable.ic_notification_sms)
                .setContentText(statusText)
                // 最低优先级：仅作状态提示，不振动、不发声
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat.from(context).notify(notificationId, updatedNotification)
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
