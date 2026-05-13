package vip.mystery0.pixel.text.receiver

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import vip.mystery0.pixel.text.notification.SmsNotificationHelper
import vip.mystery0.pixel.text.worker.SpamDetectionWorker

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isEmpty()) return

            // 合并多段短信（长短信拆包）
            val sender = messages[0]?.displayOriginatingAddress ?: return
            val body = messages.joinToString("") { it?.displayMessageBody ?: "" }
            val timestamp = messages[0]?.timestampMillis ?: System.currentTimeMillis()

            // 从 intent 取本次短信归属的 SIM 卡 subId，用于双卡分流
            val subId = intent.getIntExtra(
                "subscription",
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )

            // 写入系统短信数据库
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subId)
                }
            }

            var threadId = 0L
            var insertedUri: android.net.Uri? = null
            try {
                insertedUri =
                    context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                // 从插入后的记录中读取 thread_id，用于通知分组和后续跳转
                if (insertedUri != null) {
                    context.contentResolver.query(
                        insertedUri,
                        arrayOf(Telephony.Sms.THREAD_ID),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            threadId =
                                cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to insert SMS into database", e)
            }

            // 发送通知
            SmsNotificationHelper.showSmsNotification(
                context = context,
                sender = sender,
                body = body,
                threadId = threadId,
                messageUri = insertedUri?.toString(),
            )

            // 触发骚扰检测
            val messageId = insertedUri?.lastPathSegment?.toLongOrNull()
            if (messageId != null && threadId > 0) {
                SpamDetectionWorker.schedule(context, messageId, threadId, body)
            }
        }
    }
}
