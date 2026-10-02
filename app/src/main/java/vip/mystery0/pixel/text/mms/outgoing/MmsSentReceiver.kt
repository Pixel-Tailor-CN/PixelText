package vip.mystery0.pixel.text.mms.outgoing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.repository.OutgoingMmsRepository

/** 显式 PendingIntent 的 data 不会被平台填充 extras 改写。 */
class MmsSentReceiver : BroadcastReceiver(), KoinComponent {
    private val repository: OutgoingMmsRepository by inject()
    private val coordinator: MmsSendCoordinator by inject()
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data ?: return
        if (intent.action != ACTION || data.scheme != "pixeltext" || data.host != "mms-send" || data.pathSegments.size != 1) return
        val token = data.lastPathSegment?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: return
        val result = resultCode
        val http = if (intent.hasExtra(SmsManager.EXTRA_MMS_HTTP_STATUS)) intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0) else null
        @Suppress("DEPRECATION")
        val bytes = runCatching {
            when (val value = intent.extras?.get(SmsManager.EXTRA_MMS_DATA)) {
                null -> null // carrier service 合法允许 null SendConf。
                is ByteArray -> if (value.size <= MmsSendResult.MAX_RESPONSE_BYTES) value else byteArrayOf()
                else -> byteArrayOf() // 类型错误不能被当成“没有响应”的平台成功。
            }
        }.getOrElse { byteArrayOf() }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (repository.recordCallback(token, result, http, bytes)) coordinator.scheduleRecovery()
            } catch (_: Exception) {
                // 回调落盘失败不能臆造成功；缺失事实最终显示 UNKNOWN。
                android.util.Log.w("MmsSentReceiver", "mms callback persistence unavailable")
            } finally { pending.finish() }
        }
    }
    companion object { const val ACTION = "${BuildConfig.APPLICATION_ID}.action.MMS_SENT" }
}
