package vip.mystery0.pixel.text.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 显式私有回执入口，不依赖任何页面是否存活。 */
class SmsSentReceiver : BroadcastReceiver(), KoinComponent {
    private val coordinator: SmsSendCoordinator by inject()
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.data?.pathSegments ?: return
        if (path.size != 2) return
        val index = path[1].toIntOrNull() ?: return
        val code = resultCode
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                coordinator.complete(path[0], index, code)
            } catch (error: Exception) {
                Log.e("SmsSentReceiver", "sms callback persistence failed", error)
                coordinator.scheduleRecovery()
            } finally { pending.finish() }
        }
    }
}
