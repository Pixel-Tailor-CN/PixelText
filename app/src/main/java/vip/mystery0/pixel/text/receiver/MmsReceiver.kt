package vip.mystery0.pixel.text.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.net.toUri
import vip.mystery0.pixel.text.mms.MmsDownloadReceiver
import vip.mystery0.pixel.text.mms.WapPushPduParser
import vip.mystery0.pixel.text.notification.SmsNotificationHelper

class MmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return

        val pushData = intent.getByteArrayExtra("data")
        if (pushData == null || pushData.isEmpty()) {
            Log.w(TAG, "WAP Push data is null or empty")
            return
        }

        val notification = WapPushPduParser.parse(pushData)
        if (notification == null) {
            Log.w(TAG, "failed to parse WAP Push PDU as M-Notification.ind")
            return
        }

        Log.d(
            TAG,
            "MMS notification: from=${notification.from}, " +
                    "location=${notification.contentLocation}, " +
                    "size=${notification.messageSize}"
        )

        val subId = intent.getIntExtra(
            "subscription",
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        )

        // 1. 在 Telephony.Mms 中插入占位记录
        val mmsUri = insertMmsPlaceholder(context, notification, subId)
        if (mmsUri == null) {
            Log.e(TAG, "failed to insert MMS placeholder")
            return
        }

        // 2. 发送通知告知用户收到彩信
        val sender = notification.from ?: "未知发件人"
        val body = notification.subject ?: "收到一条彩信"
        val threadId = queryThreadId(context, mmsUri)
        SmsNotificationHelper.showSmsNotification(
            context = context,
            sender = sender,
            body = body,
            threadId = threadId,
        )

        // 3. 触发后台下载
        triggerMmsDownload(context, notification, mmsUri, subId)
    }

    private fun insertMmsPlaceholder(
        context: Context,
        notification: vip.mystery0.pixel.text.mms.MmsNotificationInd,
        subId: Int,
    ): Uri? {
        val now = System.currentTimeMillis() / 1000 // MMS 用秒级时间戳
        val values = ContentValues().apply {
            put(Telephony.Mms.DATE, now)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.MESSAGE_TYPE, 130) // MESSAGE_TYPE_RETRIEVE_CONF
            put(Telephony.Mms.CONTENT_LOCATION, notification.contentLocation)
            put(Telephony.Mms.TRANSACTION_ID, notification.transactionId)
            put(Telephony.Mms.MESSAGE_SIZE, notification.messageSize)
            if (!notification.subject.isNullOrBlank()) {
                put(Telephony.Mms.SUBJECT, notification.subject)
            }
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                put(Telephony.Mms.SUBSCRIPTION_ID, subId)
            }
            // 标记为"下载中"状态
            put(Telephony.Mms.STATUS, 128) // STATUS_DOWNLOADING
        }
        return try {
            context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "failed to insert MMS placeholder", e)
            null
        }
    }

    private fun queryThreadId(context: Context, mmsUri: Uri): Long {
        try {
            context.contentResolver.query(
                mmsUri,
                arrayOf(Telephony.Mms.THREAD_ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to query thread_id from MMS URI", e)
        }
        return 0L
    }

    private fun triggerMmsDownload(
        context: Context,
        notification: vip.mystery0.pixel.text.mms.MmsNotificationInd,
        mmsUri: Uri,
        subId: Int,
    ) {
        try {
            val downloadIntent = Intent(context, MmsDownloadReceiver::class.java).apply {
                action = MmsDownloadReceiver.ACTION_MMS_DOWNLOADED
                putExtra(MmsDownloadReceiver.EXTRA_MMS_URI, mmsUri.toString())
                putExtra(MmsDownloadReceiver.EXTRA_CONTENT_LOCATION, notification.contentLocation)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                mmsUri.hashCode(),
                downloadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val locationUri = notification.contentLocation.toUri()
            val configOverrides = Bundle()

            val smsManager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(subId)
            } else {
                context.getSystemService(SmsManager::class.java)
            }

            smsManager.downloadMultimediaMessage(
                context,
                locationUri.toString(),
                mmsUri,
                configOverrides,
                pendingIntent,
            )
            Log.d(TAG, "MMS download triggered for ${notification.contentLocation}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to trigger MMS download", e)
            // 标记下载失败
            val values = ContentValues().apply {
                put(Telephony.Mms.STATUS, 135) // STATUS_DEFERRED
            }
            context.contentResolver.update(mmsUri, values, null, null)
        }
    }
}
