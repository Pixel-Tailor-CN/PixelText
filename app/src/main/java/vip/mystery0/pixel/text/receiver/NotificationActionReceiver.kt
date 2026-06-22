package vip.mystery0.pixel.text.receiver

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
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
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.repository.ConversationCacheRepository
import vip.mystery0.pixel.text.notification.SmsNotificationHelper
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 处理通知操作按钮（"已阅" / "回复"）的 BroadcastReceiver。
 *
 * 使用 BroadcastReceiver 而非 Activity，确保在后台也能静默处理，无需唤起 UI。
 */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val conversationCacheRepository: ConversationCacheRepository by inject()

    companion object {
        private const val TAG = "NotificationActionReceiver"

        /** 将当前会话的所有未读短信 / 彩信标记为已读 */
        val ACTION_MARK_READ = "${BuildConfig.APPLICATION_ID}.action.MARK_READ"

        /** 直接从通知栏回复短信（RemoteInput inline reply） */
        val ACTION_REPLY_SMS = "${BuildConfig.APPLICATION_ID}.action.REPLY_SMS"

        /** 通知栏快捷回复的 SMS_SENT 回执 */
        val ACTION_REPLY_SMS_SENT = "${BuildConfig.APPLICATION_ID}.action.REPLY_SMS_SENT"

        /** 通知栏快捷回复的 SMS_DELIVERED 回执 */
        val ACTION_REPLY_SMS_DELIVERED =
            "${BuildConfig.APPLICATION_ID}.action.REPLY_SMS_DELIVERED"

        /** 复制验证码，并将对应消息标记为已读 */
        val ACTION_COPY_VERIFICATION_CODE =
            "${BuildConfig.APPLICATION_ID}.action.COPY_VERIFICATION_CODE"

        /** Intent extra：通知 ID，用于 cancel / update */
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        /** Intent extra：会话 thread_id，标记已读时使用 */
        const val EXTRA_THREAD_ID = "extra_thread_id"

        /** Intent extra：消息数据库 URI，优先用于精确标记单条消息已读 */
        const val EXTRA_MESSAGE_URI = "extra_message_uri"

        /** Intent extra：回复目标的手机号 / 发件人地址 */
        const val EXTRA_REPLY_ADDRESS = "extra_reply_address"

        /** Intent extra：待复制的验证码 */
        const val EXTRA_VERIFICATION_CODE = "extra_verification_code"

        private const val EXTRA_REPLY_MESSAGE_URI = "extra_reply_message_uri"
        private const val EXTRA_REPLY_REQUEST_ID = "extra_reply_request_id"
        private const val EXTRA_REPLY_PART_COUNT = "extra_reply_part_count"
        private const val EXTRA_REPLY_PART_INDEX = "extra_reply_part_index"

        /**
         * RemoteInput result key：从通知栏输入框取出回复文本时使用的 key。
         * 必须与 [SmsNotificationHelper] 中 RemoteInput.Builder 的 key 一致。
         */
        const val EXTRA_REPLY_TEXT = "extra_reply_text"

        private val replyRequestCounter = AtomicInteger(0)
        private val replySendStates = ConcurrentHashMap<Int, ReplySendState>()
        private val replyDeliveryStates = ConcurrentHashMap<Int, ReplyDeliveryState>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)

        when (intent.action) {
            ACTION_MARK_READ -> {
                if (threadId != -1L) {
                    markThreadAsRead(context, threadId)
                    syncReadStateAsync(context, threadId)
                }
                cancelNotification(context, notificationId)
            }

            ACTION_COPY_VERIFICATION_CODE -> {
                val code = intent.getStringExtra(EXTRA_VERIFICATION_CODE)
                val messageUri = intent.getStringExtra(EXTRA_MESSAGE_URI)
                if (!code.isNullOrBlank()) {
                    copyVerificationCode(context, code)
                    markMessageAsRead(context, messageUri, threadId)
                    syncReadStateAsync(context, resolveThreadId(context, messageUri, threadId))
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
                    val sent = sendSmsReply(context, notificationId, address, replyText)
                    // 发送后必须更新通知，否则系统会一直显示转圈进度条
                    if (sent) {
                        updateNotificationAfterReply(
                            context = context,
                            notificationId = notificationId,
                            statusText = "正在发送"
                        )
                    } else {
                        updateNotificationAfterReply(
                            context = context,
                            notificationId = notificationId,
                            statusText = context.getString(R.string.notification_reply_failed)
                        )
                    }
                } else {
                    Log.w(TAG, "reply skipped: replyText=$replyText, address=$address")
                    cancelNotification(context, notificationId)
                }
            }

            ACTION_REPLY_SMS_SENT -> handleReplySmsSent(context, intent, resultCode)

            ACTION_REPLY_SMS_DELIVERED -> handleReplySmsDelivered(context, intent, resultCode)
        }
    }

    private fun syncReadStateAsync(context: Context, threadId: Long?) {
        if (threadId == null || threadId <= 0L) {
            SmartspacerIntegration.notifyChanged(context)
            return
        }
        val pendingResult = goAsync()
        val cacheRepository = conversationCacheRepository
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                cacheRepository.syncThreads(listOf(threadId))
            } catch (e: Exception) {
                Log.e(TAG, "failed to sync conversation cache thread_id=$threadId", e)
            } finally {
                SmartspacerIntegration.notifyChanged(context)
                pendingResult.finish()
            }
        }
    }

    private fun resolveThreadId(context: Context, messageUri: String?, fallbackThreadId: Long): Long? {
        if (fallbackThreadId > 0L) return fallbackThreadId
        if (messageUri.isNullOrBlank()) return null
        return try {
            context.contentResolver.query(
                Uri.parse(messageUri),
                arrayOf(Telephony.Sms.THREAD_ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to resolve thread id message_uri=$messageUri", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // 内部实现
    // -------------------------------------------------------------------------

    /**
     * 将指定会话中所有未读的收件箱短信 / 彩信标记为已读。
     */
    private fun markThreadAsRead(context: Context, threadId: Long) {
        try {
            val smsUpdated = context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                readValues(),
                "${Telephony.Sms.THREAD_ID} = ? AND (${Telephony.Sms.READ} = 0 OR ${Telephony.Sms.SEEN} = 0) AND ${Telephony.Sms.TYPE} = ?",
                arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
            )
            val mmsUpdated = context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                readValues(),
                "${Telephony.Mms.THREAD_ID} = ? AND (${Telephony.Mms.READ} = 0 OR ${Telephony.Mms.SEEN} = 0) AND ${Telephony.Mms.MESSAGE_BOX} = ?",
                arrayOf(threadId.toString(), Telephony.Mms.MESSAGE_BOX_INBOX.toString())
            )
            Log.d(TAG, "mark thread read thread_id=$threadId sms=$smsUpdated mms=$mmsUpdated")
        } catch (e: Exception) {
            Log.e(TAG, "failed to mark thread $threadId as read", e)
        }
    }

    /**
     * 优先将通知对应的单条消息标记为已读；URI 不可用时回退到会话级已读。
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
    private fun sendSmsReply(
        context: Context,
        notificationId: Int,
        recipient: String,
        text: String
    ): Boolean {
        var messageUri: Uri? = null
        return try {
            val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
            val baseSmsManager: SmsManager = context.getSystemService(SmsManager::class.java)
            val smsManager = if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                baseSmsManager.createForSubscriptionId(defaultSubId)
            } else {
                Log.w(TAG, "default sms subscription unavailable, using system sms manager")
                baseSmsManager
            }
            messageUri = saveReplyOutboxToDb(context, recipient, text, defaultSubId)
            val requestId = replyRequestCounter.incrementAndGet()

            val parts = smsManager.divideMessage(text)
            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    recipient,
                    null,
                    text,
                    buildReplyPendingIntent(
                        context = context,
                        action = ACTION_REPLY_SMS_SENT,
                        requestCode = requestId * 100,
                        notificationId = notificationId,
                        messageUri = messageUri,
                        requestId = requestId,
                        partIndex = 0,
                        partCount = 1
                    ),
                    buildReplyPendingIntent(
                        context = context,
                        action = ACTION_REPLY_SMS_DELIVERED,
                        requestCode = requestId * 100 + 50,
                        notificationId = notificationId,
                        messageUri = messageUri,
                        requestId = requestId,
                        partIndex = 0,
                        partCount = 1
                    )
                )
            } else {
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                val deliveredIntents = ArrayList<PendingIntent>(parts.size)
                parts.forEachIndexed { index, _ ->
                    sentIntents += buildReplyPendingIntent(
                        context = context,
                        action = ACTION_REPLY_SMS_SENT,
                        requestCode = requestId * 100 + index,
                        notificationId = notificationId,
                        messageUri = messageUri,
                        requestId = requestId,
                        partIndex = index,
                        partCount = parts.size
                    )
                    deliveredIntents += buildReplyPendingIntent(
                        context = context,
                        action = ACTION_REPLY_SMS_DELIVERED,
                        requestCode = requestId * 100 + 50 + index,
                        notificationId = notificationId,
                        messageUri = messageUri,
                        requestId = requestId,
                        partIndex = index,
                        partCount = parts.size
                    )
                }
                smsManager.sendMultipartTextMessage(
                    recipient,
                    null,
                    parts,
                    sentIntents,
                    deliveredIntents
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to send reply to $recipient", e)
            updateReplySendResult(
                context = context,
                messageUri = messageUri?.toString(),
                resultCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE,
                success = false
            )
            false
        }
    }

    /**
     * 将已发出的短信写入系统 SMS 数据库的 Sent 目录（content://sms/sent）。
     *
     * 作为默认短信应用，[SmsManager] 只负责发送无线信号，数据库持久化由应用自行负责。
     * 不写入数据库会导致：其他短信应用看不到、本应用重启后记录丢失、对话 thread_id 无法正确关联。
     */
    private fun saveReplyOutboxToDb(
        context: Context,
        recipient: String,
        text: String,
        subId: Int,
    ): Uri? {
        try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, recipient)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, now)
                // 已发送消息默认标记为"已读"
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subId)
                }
            }
            return context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "failed to save reply outbox message", e)
            return null
        }
    }

    private fun buildReplyPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        notificationId: Int,
        messageUri: Uri?,
        requestId: Int,
        partIndex: Int,
        partCount: Int,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_REPLY_MESSAGE_URI, messageUri?.toString())
            putExtra(EXTRA_REPLY_REQUEST_ID, requestId)
            putExtra(EXTRA_REPLY_PART_INDEX, partIndex)
            putExtra(EXTRA_REPLY_PART_COUNT, partCount)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handleReplySmsSent(context: Context, intent: Intent, resultCode: Int) {
        val requestId = intent.getIntExtra(EXTRA_REPLY_REQUEST_ID, -1)
        val partCount = intent.getIntExtra(EXTRA_REPLY_PART_COUNT, 1).coerceAtLeast(1)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val messageUri = intent.getStringExtra(EXTRA_REPLY_MESSAGE_URI)

        val state = replySendStates.compute(requestId) { _, current ->
            val next = current ?: ReplySendState(partCount)
            next.receivedCount += 1
            if (resultCode != Activity.RESULT_OK && next.firstError == Activity.RESULT_OK) {
                next.firstError = resultCode
            }
            next
        } ?: ReplySendState(partCount)

        if (state.receivedCount < state.partCount) return
        replySendStates.remove(requestId)

        val success = state.firstError == Activity.RESULT_OK
        updateReplySendResult(context, messageUri, state.firstError, success)
        updateNotificationAfterReply(
            context = context,
            notificationId = notificationId,
            statusText = context.getString(
                if (success) R.string.notification_reply_sent
                else R.string.notification_reply_failed
            )
        )
    }

    private fun handleReplySmsDelivered(context: Context, intent: Intent, resultCode: Int) {
        val requestId = intent.getIntExtra(EXTRA_REPLY_REQUEST_ID, -1)
        val partCount = intent.getIntExtra(EXTRA_REPLY_PART_COUNT, 1).coerceAtLeast(1)
        val messageUri = intent.getStringExtra(EXTRA_REPLY_MESSAGE_URI)

        val state = replyDeliveryStates.compute(requestId) { _, current ->
            val next = current ?: ReplyDeliveryState(partCount)
            next.receivedCount += 1
            if (resultCode != Activity.RESULT_OK && next.firstError == Activity.RESULT_OK) {
                next.firstError = resultCode
            }
            next
        } ?: ReplyDeliveryState(partCount)

        Log.d(
            TAG,
            "reply delivery result request_id=$requestId received=${state.receivedCount}/${state.partCount} result_code=$resultCode"
        )

        if (state.receivedCount < state.partCount) return
        replyDeliveryStates.remove(requestId)
        updateReplyDeliveryResult(
            context = context,
            messageUri = messageUri,
            success = state.firstError == Activity.RESULT_OK
        )
    }

    private fun updateReplySendResult(
        context: Context,
        messageUri: String?,
        resultCode: Int,
        success: Boolean,
    ) {
        if (messageUri.isNullOrBlank()) return
        val values = ContentValues().apply {
            if (success) {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            } else {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                put(Telephony.Sms.ERROR_CODE, resultCode)
            }
        }
        try {
            context.contentResolver.update(Uri.parse(messageUri), values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "failed to update reply send result", e)
        }
    }

    private fun updateReplyDeliveryResult(
        context: Context,
        messageUri: String?,
        success: Boolean,
    ) {
        if (messageUri.isNullOrBlank()) return
        val values = ContentValues().apply {
            put(
                Telephony.Sms.STATUS,
                if (success) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED
            )
        }
        try {
            context.contentResolver.update(Uri.parse(messageUri), values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "failed to update reply delivery result", e)
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
        statusText: String,
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

    private data class ReplySendState(
        val partCount: Int,
        var receivedCount: Int = 0,
        var firstError: Int = Activity.RESULT_OK,
    )

    private data class ReplyDeliveryState(
        val partCount: Int,
        var receivedCount: Int = 0,
        var firstError: Int = Activity.RESULT_OK,
    )
}
