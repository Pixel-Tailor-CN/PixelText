package vip.mystery0.pixel.text.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.BuildConfig

class MmsReceptionResponseReceiver : BroadcastReceiver(), KoinComponent {
    private val responses: MmsReceptionResponseSender by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val key = intent.getStringExtra("key") ?: return
        val token = intent.getStringExtra("token") ?: return
        val result = resultCode
        val http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { responses.complete(key, token, result, http) }
            catch (_: Exception) { Log.w("MmsResponse", "mms response callback unavailable") }
            finally { pending.finish() }
        }
    }
    companion object { const val ACTION = "${BuildConfig.APPLICATION_ID}.action.MMS_RESPONSE_SENT" }
}
