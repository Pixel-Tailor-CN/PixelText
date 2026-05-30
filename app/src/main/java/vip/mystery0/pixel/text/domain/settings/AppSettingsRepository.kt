package vip.mystery0.pixel.text.domain.settings

import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val spamDetectionEnabled: Boolean = AppSettingsKeys.DEFAULT_SPAM_DETECTION_ENABLED,
    val muteSpamNotificationsEnabled: Boolean =
        AppSettingsKeys.DEFAULT_MUTE_SPAM_NOTIFICATIONS_ENABLED,
    val hideFullySpamConversationsEnabled: Boolean =
        AppSettingsKeys.DEFAULT_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED,
    val smartCardEnabled: Boolean = AppSettingsKeys.DEFAULT_SMART_CARD_ENABLED,
    val verificationCodeNotificationActionEnabled: Boolean =
        AppSettingsKeys.DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED,
)

interface AppSettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setSpamDetectionEnabled(enabled: Boolean)
    fun setMuteSpamNotificationsEnabled(enabled: Boolean)
    fun setHideFullySpamConversationsEnabled(enabled: Boolean)
    fun setSmartCardEnabled(enabled: Boolean)
    fun setVerificationCodeNotificationActionEnabled(enabled: Boolean)

    fun isSpamDetectionEnabled(): Boolean
    fun isMuteSpamNotificationsEnabled(): Boolean
    fun isHideFullySpamConversationsEnabled(): Boolean
    fun isSmartCardEnabled(): Boolean
    fun isVerificationCodeNotificationActionEnabled(): Boolean
}

object AppSettingsKeys {
    const val PREFS_NAME = "app_settings"
    const val KEY_SPAM_DETECTION_ENABLED = "spam_detection_enabled"
    const val KEY_MUTE_SPAM_NOTIFICATIONS_ENABLED = "mute_spam_notifications_enabled"
    const val KEY_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED =
        "hide_fully_spam_conversations_enabled"
    const val KEY_SMART_CARD_ENABLED = "smart_card_enabled"
    const val KEY_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED =
        "verification_code_notification_action_enabled"

    const val DEFAULT_SPAM_DETECTION_ENABLED = true
    const val DEFAULT_MUTE_SPAM_NOTIFICATIONS_ENABLED = false
    const val DEFAULT_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED = true
    const val DEFAULT_SMART_CARD_ENABLED = true
    const val DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED = true
}
