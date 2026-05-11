package vip.mystery0.pixel.text.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * HeadlessSmsSendService 是 Android 系统对“默认短信应用”的强制要求组件之一。
 * 
 * 它的主要作用是处理“通过短信快速回复通话”（Respond via message）的功能。
 * 当用户在来电界面选择“发送短信回复”时，系统会发送带有 [Intent.ACTION_RESPOND_VIA_MESSAGE] 的 Intent 到此服务。
 */
class HeadlessSmsSendService : Service() {
    companion object {
        private const val TAG = "HeadlessSmsSendService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "android.intent.action.RESPOND_VIA_MESSAGE") {
            val message = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri = intent.data

            Log.d(TAG, "Headless SMS requested. URI: $uri, Message: $message")

            if (uri != null && !message.isNullOrBlank()) {
                val recipient = getRecipient(uri)
                if (recipient.isNotBlank()) {
                    sendSms(recipient, message)
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

    private fun sendSms(recipient: String, message: String) {
        try {
            val smsManager = getSystemService(android.telephony.SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, message, null, null)
            }
            Log.d(TAG, "Headless SMS sent successfully to $recipient")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send headless SMS to $recipient", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
