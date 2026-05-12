package vip.mystery0.pixel.text.service

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * HeadlessSmsSendService 是 Android 系统对"默认短信应用"的强制要求组件之一。
 *
 * 它的主要作用是处理"通过短信快速回复通话"（Respond via message）的功能。
 * 当用户在来电界面选择"发送短信回复"时，系统会发送带有 [Intent.ACTION_RESPOND_VIA_MESSAGE] 的 Intent 到此服务。
 */
class HeadlessSmsSendService : Service() {
    companion object {
        private const val TAG = "HeadlessSmsSendService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "android.intent.action.RESPOND_VIA_MESSAGE") {
            val message = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri = intent.data

            if (uri != null && !message.isNullOrBlank()) {
                val recipient = getRecipient(uri)
                if (recipient.isNotBlank()) {
                    val subId = intent.getIntExtra(
                        "android.telephony.extra.SUBSCRIPTION_INDEX",
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    )
                    sendSms(recipient, message, subId)
                }
            }
        }
        // 处理完后立即停止服务
        stopSelf()
        return START_NOT_STICKY
    }

    private fun getRecipient(uri: android.net.Uri): String {
        val baseUri = uri.schemeSpecificPart
        // 去掉可能存在的查询参数（如 ?body=...）
        return baseUri.split('?')[0]
    }

    private fun sendSms(recipient: String, message: String, subId: Int) {
        try {
            val smsManager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                getSystemService(SmsManager::class.java)
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, message, null, null)
            }
            // 作为默认短信应用，发送后必须把记录写回系统数据库，
            // 否则任何短信应用（包括本应用自己）都看不到这条快速回复。
            saveSentMessageToDb(recipient, message, subId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send headless SMS to $recipient", e)
        }
    }

    private fun saveSentMessageToDb(recipient: String, message: String, subId: Int) {
        try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, recipient)
                put(Telephony.Sms.BODY, message)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.DATE_SENT, now)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subId)
                }
            }
            contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save headless SMS to DB", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
