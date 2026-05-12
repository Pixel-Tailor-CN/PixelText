package vip.mystery0.pixel.text.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import vip.mystery0.pixel.text.MainActivity
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.receiver.NotificationActionReceiver

object SmsNotificationHelper {

    const val CHANNEL_ID_SMS = "channel_new_sms"
    private const val CHANNEL_NAME = "新短信"
    private const val CHANNEL_DESC = "收到新短信时的通知"

    /**
     * 在 Application.onCreate() 中调用，注册通知渠道（Android 8.0+ 必须）。
     */
    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID_SMS,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            enableLights(true)
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 发送新短信通知，带"已阅"和"回复"操作按钮。
     *
     * @param context       上下文
     * @param sender        发件人（号码或联系人名称）
     * @param body          短信内容
     * @param threadId      会话 ID，用于跳转、分组和标记已读
     * @param messageUri    插入数据库后返回的 URI（目前未使用，预留）
     */
    fun showSmsNotification(
        context: Context,
        sender: String,
        body: String,
        threadId: Long = 0L,
        messageUri: String? = null,
    ) {
        // Android 13+ 需要运行时授权 POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        // 通知 ID：以 threadId 为 ID 保证同一会话只保留最新一条（覆盖更新）
        val notificationId =
            if (threadId != 0L) threadId.toInt() else System.currentTimeMillis().toInt()

        // ── 主体点击：打开 MainActivity 并传入 threadId 和发件人地址 ────────
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            putExtra(MainActivity.EXTRA_ADDRESS, sender)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            // requestCode 区分不同会话的 PendingIntent，避免复用旧 Intent
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Action 1："已阅" ─────────────────────────────────────────────────
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            // requestCode = notificationId * 10 + 1，与其他 action 区分
            notificationId * 10 + 1,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Action 2："回复" ─────────────────────────────────────────────────
        val replyLabel = context.getString(R.string.notification_reply_hint)
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.EXTRA_REPLY_TEXT)
            .setLabel(replyLabel)
            .build()

        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY_SMS
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
            putExtra(NotificationActionReceiver.EXTRA_REPLY_ADDRESS, sender)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 2,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // Required for RemoteInput
        )

        val replyAction = NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.notification_action_reply),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // ── 构建通知 ─────────────────────────────────────────────────────────
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SMS)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle(sender)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setGroup("sms_group_$threadId")
            // Action 1：已阅
            .addAction(
                0,
                context.getString(R.string.notification_action_mark_read),
                markReadPendingIntent
            )
            // Action 2：回复
            .addAction(replyAction)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
