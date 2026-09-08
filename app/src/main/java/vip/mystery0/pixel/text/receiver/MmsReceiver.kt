package vip.mystery0.pixel.text.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.mms.MmsIncomingPduHandler
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler

/** 整段接收放在受控 IO 中；回调期间不解析下载正文。 */
class MmsReceiver : BroadcastReceiver(), KoinComponent {
    private val incoming: MmsIncomingPduHandler by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data")?.takeIf { it.isNotEmpty() } ?: return
        val subId = intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { incoming.handleIncoming(data, subId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                Log.w("MmsReceiver", "mms incoming processing unavailable")
                MessageMirrorScheduler(context).schedule()
            } finally { pending.finish() }
        }
    }
}
