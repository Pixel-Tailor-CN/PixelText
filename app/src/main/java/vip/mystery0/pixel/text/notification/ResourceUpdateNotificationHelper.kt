package vip.mystery0.pixel.text.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import vip.mystery0.pixel.text.MainActivity
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateDetail

object ResourceUpdateNotificationHelper {
    const val CHANNEL_ID_RESOURCE_UPDATES = "channel_resource_updates"
    private const val CHANNEL_NAME = "资源更新"
    private const val CHANNEL_DESC = "规则和模型资源更新提醒"
    private const val NOTIFICATION_ID_RESOURCE_UPDATE = 20020

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID_RESOURCE_UPDATES,
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

    @SuppressLint("MissingPermission")
    fun showUpdateAvailable(context: Context, detail: ResourceUpdateDetail) {
        if (!canPostNotifications(context)) return
        createNotificationChannel(context)
        cancel(context)

        val contentText = "有新的规则或模型资源可用，点按查看"
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_RESOURCE_UPDATE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                putExtra(MainActivity.EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_RESOURCE_UPDATES)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle("发现资源更新")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSubText("模型 ${detail.modelVersion} / 规则 ${detail.ruleVersion}")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID_RESOURCE_UPDATE, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_RESOURCE_UPDATE)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
