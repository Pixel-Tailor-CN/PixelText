package vip.mystery0.pixel.text.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.sms.SmsSendCoordinator

/** 来电快捷回复与会话页使用相同的持久回执链路，服务只存活到平台接纳。 */
class HeadlessSmsSendService : Service(), KoinComponent {
    private val sender: SmsSendCoordinator by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val recipient = intent?.data?.schemeSpecificPart?.substringBefore('?')
        if (intent?.action != "android.intent.action.RESPOND_VIA_MESSAGE" || body.isNullOrBlank() || recipient.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        scope.launch {
            try {
                sender.send(recipient, body, subId)
            } catch (error: Exception) {
                Log.e("HeadlessSmsSendService", "headless sms submission failed", error)
            } finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
