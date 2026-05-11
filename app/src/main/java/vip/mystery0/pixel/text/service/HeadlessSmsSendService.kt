package vip.mystery0.pixel.text.service

import android.app.IntentService
import android.content.Intent
import android.util.Log

class HeadlessSmsSendService : IntentService("HeadlessSmsSendService") {
    companion object {
        private const val TAG = "HeadlessSmsSendService"
    }

    override fun onHandleIntent(intent: Intent?) {
        if (intent?.action == "android.intent.action.RESPOND_VIA_MESSAGE") {
            val message = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri = intent.data
            Log.d(TAG, "Headless SMS requested. URI: $uri, Message: $message")
            // TODO: Implement actual SMS sending logic using SmsManager
        }
    }
}
