package vip.mystery0.pixel.text.domain.settings

import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val spamDetectionEnabled: Boolean = AppSettingsKeys.DEFAULT_SPAM_DETECTION_ENABLED,
    val muteSpamNotificationsEnabled: Boolean =
        AppSettingsKeys.DEFAULT_MUTE_SPAM_NOTIFICATIONS_ENABLED,
    val spamAutoAction: SpamAutoAction = AppSettingsKeys.DEFAULT_SPAM_AUTO_ACTION,
    val hideFullySpamConversationsEnabled: Boolean =
        AppSettingsKeys.DEFAULT_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED,
    val smartCardEnabled: Boolean = AppSettingsKeys.DEFAULT_SMART_CARD_ENABLED,
    val verificationCodeNotificationActionEnabled: Boolean =
        AppSettingsKeys.DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED,
    val hideVerificationCodeOnLockScreenEnabled: Boolean =
        AppSettingsKeys.DEFAULT_HIDE_VERIFICATION_CODE_ON_LOCK_SCREEN_ENABLED,
    val conversationDetailTextScale: Float =
        AppSettingsKeys.DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE,
    val ruleResourceVersion: String = AppSettingsKeys.DEFAULT_RESOURCE_VERSION,
    val spamModelResourceVersion: String = AppSettingsKeys.DEFAULT_RESOURCE_VERSION,
    val vocabResourceVersion: String = AppSettingsKeys.DEFAULT_RESOURCE_VERSION,
    val resourceUpdatedAt: Long = AppSettingsKeys.DEFAULT_RESOURCE_UPDATED_AT,
)

enum class SpamAutoAction(val storageValue: String) {
    NONE("none"),
    MARK_READ("mark_read"),
    DELETE("delete");

    companion object {
        fun fromStorageValue(value: String?): SpamAutoAction {
            return entries.firstOrNull { it.storageValue == value } ?: NONE
        }
    }
}

interface AppSettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setSpamDetectionEnabled(enabled: Boolean)
    fun setMuteSpamNotificationsEnabled(enabled: Boolean)
    fun setSpamAutoAction(action: SpamAutoAction)
    fun setHideFullySpamConversationsEnabled(enabled: Boolean)
    fun setSmartCardEnabled(enabled: Boolean)
    fun setVerificationCodeNotificationActionEnabled(enabled: Boolean)
    fun setHideVerificationCodeOnLockScreenEnabled(enabled: Boolean)
    fun setConversationDetailTextScale(scale: Float)
    fun setRuleResourceVersion(version: String)
    fun setSpamModelResourceVersion(version: String)
    fun setVocabResourceVersion(version: String)
    fun setResourceUpdatedAt(timestamp: Long)

    fun isSpamDetectionEnabled(): Boolean
    fun isMuteSpamNotificationsEnabled(): Boolean
    fun getSpamAutoAction(): SpamAutoAction
    fun isHideFullySpamConversationsEnabled(): Boolean
    fun isSmartCardEnabled(): Boolean
    fun isVerificationCodeNotificationActionEnabled(): Boolean
    fun isHideVerificationCodeOnLockScreenEnabled(): Boolean
    fun getConversationDetailTextScale(): Float
    fun getRuleResourceVersion(): String
    fun getSpamModelResourceVersion(): String
    fun getVocabResourceVersion(): String
    fun getResourceUpdatedAt(): Long
}

object AppSettingsKeys {
    const val PREFS_NAME = "app_settings"
    const val KEY_SPAM_DETECTION_ENABLED = "spam_detection_enabled"
    const val KEY_MUTE_SPAM_NOTIFICATIONS_ENABLED = "mute_spam_notifications_enabled"
    const val KEY_SPAM_AUTO_ACTION = "spam_auto_action"
    const val KEY_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED =
        "hide_fully_spam_conversations_enabled"
    const val KEY_SMART_CARD_ENABLED = "smart_card_enabled"
    const val KEY_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED =
        "verification_code_notification_action_enabled"
    const val KEY_HIDE_VERIFICATION_CODE_ON_LOCK_SCREEN_ENABLED =
        "hide_verification_code_on_lock_screen_enabled"
    const val KEY_CONVERSATION_DETAIL_TEXT_SCALE = "conversation_detail_text_scale"
    const val KEY_RULE_RESOURCE_VERSION = "rule_resource_version"
    const val KEY_SPAM_MODEL_RESOURCE_VERSION = "spam_model_resource_version"
    const val KEY_VOCAB_RESOURCE_VERSION = "vocab_resource_version"
    const val KEY_RESOURCE_UPDATED_AT = "resource_updated_at"

    const val DEFAULT_SPAM_DETECTION_ENABLED = true
    const val DEFAULT_MUTE_SPAM_NOTIFICATIONS_ENABLED = false
    val DEFAULT_SPAM_AUTO_ACTION = SpamAutoAction.NONE
    const val DEFAULT_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED = false
    const val DEFAULT_SMART_CARD_ENABLED = true
    const val DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED = true
    const val DEFAULT_HIDE_VERIFICATION_CODE_ON_LOCK_SCREEN_ENABLED = true
    const val DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE = 1f
    const val DEFAULT_RESOURCE_VERSION = "builtin"
    const val DEFAULT_RESOURCE_UPDATED_AT = 0L
}
