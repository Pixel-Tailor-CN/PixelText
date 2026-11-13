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
    private const val NOTIFICATION_ID_RESOURCE_UPDATE = 20020

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID_RESOURCE_UPDATES,
            context.getString(R.string.sys_channel_resource_updates_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.sys_channel_resource_updates_desc)
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

        val contentText = context.getString(R.string.sys_resource_update_available_text)
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
            .setContentTitle(context.getString(R.string.sys_resource_update_available_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSubText(
                context.getString(
                    R.string.sys_resource_update_available_subtext,
                    detail.modelVersion,
                    detail.ruleVersion,
                )
            )
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
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
