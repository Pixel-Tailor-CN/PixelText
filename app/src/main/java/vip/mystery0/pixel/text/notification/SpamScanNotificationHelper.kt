package vip.mystery0.pixel.text.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import vip.mystery0.pixel.text.R

object SpamScanNotificationHelper {
    const val CHANNEL_ID_SPAM_SCAN = "channel_spam_scan"

    private const val CHANNEL_NAME = "骚扰识别"
    private const val CHANNEL_DESC = "历史短信骚扰识别进度"
    private const val NOTIFICATION_ID_SCAN_PROGRESS = 20010

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID_SPAM_SCAN,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun showProgress(context: Context, processed: Int, total: Int) {
        if (!canPostNotifications(context)) return
        createNotificationChannel(context)

        val percent = percent(processed, total)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SPAM_SCAN)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle("正在识别历史短信")
            .setContentText("$processed/$total $percent%")
            .setProgress(total.coerceAtLeast(1), processed.coerceAtMost(total), total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SCAN_PROGRESS, notification)
    }

    fun showCompleted(context: Context, processed: Int, spamCount: Int) {
        if (!canPostNotifications(context)) return
        createNotificationChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SPAM_SCAN)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle("历史短信识别完成")
            .setContentText("已识别 $processed 条短信，发现 $spamCount 条骚扰短信")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SCAN_PROGRESS, notification)
    }

    fun showFailed(context: Context) {
        if (!canPostNotifications(context)) return
        createNotificationChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SPAM_SCAN)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle("历史短信识别失败")
            .setContentText("请稍后重试")
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SCAN_PROGRESS, notification)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun percent(processed: Int, total: Int): Int {
        if (total <= 0) return 100
        return ((processed.toFloat() / total.toFloat()) * 100).toInt().coerceIn(0, 100)
    }
}
