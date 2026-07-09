package vip.mystery0.pixel.text.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import vip.mystery0.pixel.text.MainActivity
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionConfig
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionType
import vip.mystery0.pixel.text.domain.settings.defaultLabelTemplate
import vip.mystery0.pixel.text.domain.settings.defaultOrder
import vip.mystery0.pixel.text.domain.settings.normalizeNotificationQuickActionConfigs
import vip.mystery0.pixel.text.domain.settings.preferenceLabelKey
import vip.mystery0.pixel.text.domain.settings.preferenceOrderKey
import vip.mystery0.pixel.text.domain.settings.renderLabel
import vip.mystery0.pixel.text.receiver.NotificationActionReceiver

object SmsNotificationHelper {

    const val CHANNEL_ID_SMS = "channel_new_sms"
    private const val CHANNEL_NAME = "新短信"
    private const val CHANNEL_DESC = "收到新短信时的通知"
    private const val TAG = "SmsNotificationHelper"

    private var messageParser: MessageParser? = null

    /**
     * 在 Application.onCreate() 中调用，注册通知渠道。
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
     * 发送新短信通知。
     *
     * 普通短信显示“已阅”和“回复”，验证码短信会额外显示“复制验证码”操作。
     * 三个操作的顺序和文案都由设置决定。
     */
    fun showSmsNotification(
        context: Context,
        sender: String,
        body: String,
        threadId: Long = 0L,
        messageUri: String? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notificationId =
            if (threadId != 0L) threadId.toInt() else System.currentTimeMillis().toInt()
        val actionConfigs = readNotificationQuickActionConfigs(context)
        val actionConfigByType = actionConfigs.associateBy { it.type }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            putExtra(MainActivity.EXTRA_ADDRESS, sender)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + 1,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            0,
            actionConfigByType[NotificationQuickActionType.MARK_READ]?.labelTemplate
                ?: NotificationQuickActionType.MARK_READ.defaultLabelTemplate(),
            markReadPendingIntent
        ).build()

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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            0,
            actionConfigByType[NotificationQuickActionType.REPLY]?.labelTemplate
                ?: NotificationQuickActionType.REPLY.defaultLabelTemplate(),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val verificationCode = parseVerificationCode(context, sender, body)
        val copyCodeAction = verificationCode?.let { code ->
            val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_COPY_VERIFICATION_CODE
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
                putExtra(NotificationActionReceiver.EXTRA_MESSAGE_URI, messageUri)
                putExtra(NotificationActionReceiver.EXTRA_VERIFICATION_CODE, code)
            }
            val copyPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId * 10 + 3,
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                0,
                actionConfigByType[NotificationQuickActionType.COPY_CODE]
                    ?.renderLabel(code)
                    ?: code,
                copyPendingIntent
            ).build()
        }

        val actionMap = buildMap<NotificationQuickActionType, NotificationCompat.Action> {
            put(NotificationQuickActionType.MARK_READ, markReadAction)
            put(NotificationQuickActionType.REPLY, replyAction)
            copyCodeAction?.let {
                put(NotificationQuickActionType.COPY_CODE, it)
            }
        }
        val orderedActions = orderNotificationActions(actionConfigs, actionMap)

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID_SMS)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle(sender)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setGroup("sms_group_$threadId")

        verificationCode
            ?.takeIf { shouldHideVerificationCodeOnLockScreen(context) }
            ?.let { code ->
                notificationBuilder
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        buildLockScreenSafeNotification(
                            context = context,
                            sender = sender,
                            body = body,
                            threadId = threadId,
                            verificationCode = code,
                            contentPendingIntent = contentPendingIntent,
                            actions = orderNotificationActions(
                                actionConfigs,
                                actionMap.filterKeys {
                                    it != NotificationQuickActionType.COPY_CODE
                                }
                            )
                        )
                    )
            }

        val notification = notificationBuilder
            .apply {
                orderedActions.forEach(::addAction)
            }
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun parseVerificationCode(context: Context, sender: String, body: String): String? {
        val prefs = context.getSharedPreferences(AppSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val isActionEnabled = prefs.getBoolean(
            AppSettingsKeys.KEY_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED,
            AppSettingsKeys.DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED
        )
        if (!isActionEnabled) return null

        return try {
            val result = getMessageParser(context).parse(sender, body)
            (result as? ParsedResult.VerificationCode)?.code
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse verification code for notification", e)
            null
        }
    }

    private fun shouldHideVerificationCodeOnLockScreen(context: Context): Boolean {
        val prefs = context.getSharedPreferences(AppSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val isActionEnabled = prefs.getBoolean(
            AppSettingsKeys.KEY_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED,
            AppSettingsKeys.DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED
        )
        val isHideEnabled = prefs.getBoolean(
            AppSettingsKeys.KEY_HIDE_VERIFICATION_CODE_ON_LOCK_SCREEN_ENABLED,
            AppSettingsKeys.DEFAULT_HIDE_VERIFICATION_CODE_ON_LOCK_SCREEN_ENABLED
        )
        return isActionEnabled && isHideEnabled
    }

    private fun buildLockScreenSafeNotification(
        context: Context,
        sender: String,
        body: String,
        threadId: Long,
        verificationCode: String,
        contentPendingIntent: PendingIntent,
        actions: List<NotificationCompat.Action>,
    ): Notification {
        val maskedBody = maskVerificationCode(body, verificationCode)
        return NotificationCompat.Builder(context, CHANNEL_ID_SMS)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle(sender)
            .setContentText(maskedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(maskedBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setGroup("sms_group_$threadId")
            .apply {
                actions.forEach(::addAction)
            }
            .build()
    }

    private fun maskVerificationCode(body: String, verificationCode: String): String {
        if (verificationCode.isBlank()) return body
        val mask = "*".repeat(verificationCode.length)
        return body.replace(verificationCode, mask)
    }

    private fun getMessageParser(context: Context): MessageParser {
        val appContext = context.applicationContext
        return messageParser ?: MessageParser(appContext, HubResourceStore(appContext)).also {
            messageParser = it
        }
    }

    private fun readNotificationQuickActionConfigs(context: Context): List<NotificationQuickActionConfig> {
        val prefs = context.getSharedPreferences(AppSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return NotificationQuickActionType.entries.map { type ->
            NotificationQuickActionConfig(
                type = type,
                labelTemplate = prefs.getString(
                    type.preferenceLabelKey(),
                    type.defaultLabelTemplate()
                ) ?: type.defaultLabelTemplate(),
                order = prefs.getInt(type.preferenceOrderKey(), type.defaultOrder())
            )
        }.normalizeNotificationQuickActionConfigs()
    }

    private fun orderNotificationActions(
        actionConfigs: List<NotificationQuickActionConfig>,
        actions: Map<NotificationQuickActionType, NotificationCompat.Action>,
    ): List<NotificationCompat.Action> {
        return actionConfigs.mapNotNull { config -> actions[config.type] }
    }

    fun cancelThreadNotification(context: Context, threadId: Long) {
        if (threadId <= 0L) return
        NotificationManagerCompat.from(context).cancel(threadId.toInt())
    }

    fun cancelThreadNotifications(context: Context, threadIds: Set<Long>) {
        threadIds.forEach { cancelThreadNotification(context, it) }
    }
}
