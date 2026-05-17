package vip.mystery0.pixel.text.domain.settings

import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val spamDetectionEnabled: Boolean = AppSettingsKeys.DEFAULT_SPAM_DETECTION_ENABLED,
    val smartCardEnabled: Boolean = AppSettingsKeys.DEFAULT_SMART_CARD_ENABLED,
    val verificationCodeNotificationActionEnabled: Boolean =
        AppSettingsKeys.DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED,
)

interface AppSettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setSpamDetectionEnabled(enabled: Boolean)
    fun setSmartCardEnabled(enabled: Boolean)
    fun setVerificationCodeNotificationActionEnabled(enabled: Boolean)

    fun isSpamDetectionEnabled(): Boolean
    fun isSmartCardEnabled(): Boolean
    fun isVerificationCodeNotificationActionEnabled(): Boolean
}

object AppSettingsKeys {
    const val PREFS_NAME = "app_settings"
    const val KEY_SPAM_DETECTION_ENABLED = "spam_detection_enabled"
    const val KEY_SMART_CARD_ENABLED = "smart_card_enabled"
    const val KEY_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED =
        "verification_code_notification_action_enabled"

    const val DEFAULT_SPAM_DETECTION_ENABLED = true
    const val DEFAULT_SMART_CARD_ENABLED = true
    const val DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED = true
}
