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

/** 异步广播只等待原事件落盘及调度，不等待后台解析和 Provider 工作。 */
class MmsReceiver : BroadcastReceiver(), KoinComponent {
    private val incoming: MmsIncomingPduHandler by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data")?.takeIf { it.isNotEmpty() } ?: return
        val subId = intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val received = System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { incoming.enqueue(data, subId, received) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                Log.w("MmsReceiver", "mms incoming processing unavailable")
                MessageMirrorScheduler(context).schedule()
            } finally { pending.finish() }
        }
    }
}
