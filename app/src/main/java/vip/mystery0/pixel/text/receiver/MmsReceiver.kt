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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.repository.SenderProfileRepository
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.mms.MmsDownloadCoordinator
import vip.mystery0.pixel.text.mms.WapPushPduParser
import vip.mystery0.pixel.text.notification.SmsNotificationHelper

class MmsReceiver : BroadcastReceiver(), KoinComponent {
    private val senderProfileRepository: SenderProfileRepository by inject()
    private val contactDataSource: ContactDataSource by inject()
    private val downloads: MmsDownloadCoordinator by inject()
    companion object {
        private const val TAG = "MmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return

        val pushData = intent.getByteArrayExtra("data")
        if (pushData == null || pushData.isEmpty()) {
            Log.w(TAG, "wap push data missing")
            return
        }

        val notification = WapPushPduParser.parse(pushData)
        if (notification == null) {
            Log.w(TAG, "failed to parse WAP Push PDU as M-Notification.ind")
            return
        }

        Log.d(
            TAG,
            "mms notification received"
        )

        val subId = intent.getIntExtra(
            "subscription",
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        )
        // 系统可能重复投递同一个通知；在插入前按事务、位置和接收卡去重。
        val duplicate = runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf("_id"),
                "tr_id = ? AND ct_l = ? AND sub_id = ?",
                arrayOf(notification.transactionId, notification.contentLocation, subId.toString()), null,
            )?.use { it.moveToFirst() } ?: error("mms dedup query unavailable")
        }.getOrElse {
            Log.w(TAG, "mms dedup unavailable")
            return
        }
        if (duplicate) return

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
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val profile = runCatching {
                    senderProfileRepository.findByNumber(sender)
                }.getOrNull()
                SmsNotificationHelper.showSmsNotification(
                    context = context,
                    sender = sender,
                    body = body,
                    threadId = threadId,
                    displaySender = contactDataSource.getDisplayName(sender)
                        ?: profile?.displayName
                        ?: sender,
                    avatarPath = profile?.avatarPath,
                )
                vip.mystery0.pixel.text.worker.MessageMirrorScheduler(context).schedule()
                runCatching { downloads.requestDownload(mmsUri.lastPathSegment!!.toLong(), false) }
                    .onFailure { Log.w(TAG, "mms automatic download unavailable category=${it.javaClass.simpleName}") }
            } finally {
                pendingResult.finish()
            }
        }


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
            put(Telephony.Mms.MESSAGE_TYPE, 130) // NotificationInd
            put(Telephony.Mms.CONTENT_LOCATION, notification.contentLocation)
            put(Telephony.Mms.TRANSACTION_ID, notification.transactionId)
            put(Telephony.Mms.MESSAGE_SIZE, notification.messageSize)
            put(Telephony.Mms.EXPIRY, notification.expiry)
            if (!notification.subject.isNullOrBlank()) {
                put(Telephony.Mms.SUBJECT, notification.subject.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1))
                put(Telephony.Mms.SUBJECT_CHARSET, 106)
            }
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                put(Telephony.Mms.SUBSCRIPTION_ID, subId)
            }
            // 尚未开始下载
            put(Telephony.Mms.STATUS, 0) // 下载状态由独立请求管理
        }
        return try {
            notification.from?.takeIf { it.isNotBlank() }?.let {
                putThread(values, context, it)
            }
            context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)?.also { uri ->
                notification.from?.takeIf { it.isNotBlank() }?.let { sender ->
                    context.contentResolver.insert("$uri/addr".toUri(), ContentValues().apply {
                        put("address", sender.substringBefore("/TYPE="))
                        put("type", 137)
                        put("charset", 106)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to insert MMS placeholder", e)
            null
        }
    }

    private fun putThread(values: ContentValues, context: Context, sender: String) {
        runCatching { Telephony.Threads.getOrCreateThreadId(context, sender.substringBefore("/TYPE=")) }
            .getOrNull()?.let { values.put(Telephony.Mms.THREAD_ID, it) }
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

}
