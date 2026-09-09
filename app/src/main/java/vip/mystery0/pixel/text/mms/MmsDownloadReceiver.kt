package vip.mystery0.pixel.text.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.BuildConfig

/** 系统仅写入临时 PDU，回调后解析并落库，进程中断由请求日志恢复。 */
class MmsDownloadReceiver : BroadcastReceiver(), KoinComponent {
    private val downloads: MmsDownloadCoordinator by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_DOWNLOADED) return
        val id = intent.getLongExtra("mms_id", -1)
        val token = intent.getStringExtra("token") ?: return
        if (id <= 0) return
        val result = resultCode
        val httpStatus = intent.getIntExtra(android.telephony.SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                downloads.complete(id, token, result, httpStatus)
            } catch (error: Exception) {
                Log.w("MmsDownloadReceiver", "mms completion failed error=${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
    companion object {
        const val ACTION_MMS_DOWNLOADED = "${BuildConfig.APPLICATION_ID}.action.MMS_DOWNLOADED"
    }
}
