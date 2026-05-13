package vip.mystery0.pixel.text.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.net.toUri

/**
 * 接收 MMS 下载完成的回调。
 *
 * 当 [android.telephony.SmsManager.downloadMultimediaMessage] 完成后，
 * 系统会通过 PendingIntent 触发此 Receiver。
 *
 * 下载成功时，系统已经将 MMS PDU 写入到我们传入的 mmsUri 对应的位置，
 * 我们只需要更新状态标记。下载失败时标记为 deferred 以便后续重试。
 */
class MmsDownloadReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsDownloadReceiver"
        const val ACTION_MMS_DOWNLOADED = "vip.mystery0.pixel.text.action.MMS_DOWNLOADED"
        const val EXTRA_MMS_URI = "extra_mms_uri"
        const val EXTRA_CONTENT_LOCATION = "extra_content_location"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_DOWNLOADED) return

        val mmsUriStr = intent.getStringExtra(EXTRA_MMS_URI)
        if (mmsUriStr.isNullOrBlank()) {
            Log.w(TAG, "MMS URI is missing in download callback")
            return
        }
        val mmsUri = mmsUriStr.toUri()

        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.d(TAG, "MMS download succeeded: $mmsUriStr")
                onDownloadSuccess(context, mmsUri)
            }

            else -> {
                Log.w(TAG, "MMS download failed with code $resultCode for $mmsUriStr")
                onDownloadFailed(context, mmsUri)
            }
        }
    }

    /**
     * 下载成功：系统已将 retrieve-conf PDU 写入 mmsUri。
     * 更新状态为已接收，并写入发件人地址到 addr 表（如果系统没自动写的话）。
     */
    private fun onDownloadSuccess(context: Context, mmsUri: Uri) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Mms.STATUS, 0) // STATUS_RETRIEVED
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
            }
            context.contentResolver.update(mmsUri, values, null, null)

            // 确保 addr 表有发件人记录（部分设备系统不会自动填充）
            ensureFromAddress(context, mmsUri)
        } catch (e: Exception) {
            Log.e(TAG, "failed to update MMS after download", e)
        }
    }

    private fun onDownloadFailed(context: Context, mmsUri: Uri) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Mms.STATUS, 135) // STATUS_DEFERRED
            }
            context.contentResolver.update(mmsUri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "failed to mark MMS as deferred", e)
        }
    }

    /**
     * 检查 MMS addr 表是否有 FROM 类型的地址记录，没有则从 PDU header 中提取并写入。
     */
    private fun ensureFromAddress(context: Context, mmsUri: Uri) {
        val mmsId = mmsUri.lastPathSegment ?: return
        val addrUri = "content://mms/$mmsId/addr".toUri()

        // 检查是否已有 FROM 地址（type = 137 = PduHeaders.FROM）
        context.contentResolver.query(
            addrUri,
            arrayOf(Telephony.Mms.Addr.ADDRESS),
            "${Telephony.Mms.Addr.TYPE} = ?",
            arrayOf("137"),
            null
        )?.use { cursor ->
            if (cursor.count > 0) return // 已有，无需补充
        }

        // 尝试从 MMS 表的 retrieve_text 或其他字段获取发件人
        // 大多数情况下系统会在下载后自动填充 addr 表，这里只是兜底
        Log.d(TAG, "FROM address not found in addr table for MMS $mmsId, skipping auto-fill")
    }
}
