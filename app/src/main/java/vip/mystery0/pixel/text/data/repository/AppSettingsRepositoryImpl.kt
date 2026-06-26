package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vip.mystery0.pixel.text.domain.settings.AppSettings
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.settings.ConversationSwipeAction
import vip.mystery0.pixel.text.domain.settings.MessageTimeDisplayFormat
import vip.mystery0.pixel.text.domain.settings.SpamAutoAction

class AppSettingsRepositoryImpl(context: Context) : AppSettingsRepository {
    private val prefs =
        context.getSharedPreferences(AppSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun setSpamDetectionEnabled(enabled: Boolean) {
        updatePrefs { putBoolean(AppSettingsKeys.KEY_SPAM_DETECTION_ENABLED, enabled) }
    }

    override fun setMuteSpamNotificationsEnabled(enabled: Boolean) {
        updatePrefs { putBoolean(AppSettingsKeys.KEY_MUTE_SPAM_NOTIFICATIONS_ENABLED, enabled) }
    }

    override fun setSpamAutoAction(action: SpamAutoAction) {
        updatePrefs { putString(AppSettingsKeys.KEY_SPAM_AUTO_ACTION, action.storageValue) }
    }

    override fun setHideFullySpamConversationsEnabled(enabled: Boolean) {
        updatePrefs {
            putBoolean(AppSettingsKeys.KEY_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED, enabled)
        }
    }

    override fun setSmartCardEnabled(enabled: Boolean) {
        updatePrefs { putBoolean(AppSettingsKeys.KEY_SMART_CARD_ENABLED, enabled) }
    }

    override fun setVerificationCodeNotificationActionEnabled(enabled: Boolean) {
        updatePrefs {
            putBoolean(AppSettingsKeys.KEY_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED, enabled)
        }
    }

    override fun setHideVerificationCodeOnLockScreenEnabled(enabled: Boolean) {
        updatePrefs {
            putBoolean(
                AppSettingsKeys.KEY_HIDE_VERIFICATION_CODE_ON_LOCK_SCREEN_ENABLED,
                enabled
            )
        }
    }

    override fun setMessageTimeDisplayFormat(format: MessageTimeDisplayFormat) {
        updatePrefs {
            putString(AppSettingsKeys.KEY_MESSAGE_TIME_DISPLAY_FORMAT, format.storageValue)
        }
    }

    override fun setRightSwipeAction(action: ConversationSwipeAction) {
        updatePrefs {
            putString(AppSettingsKeys.KEY_RIGHT_SWIPE_ACTION, action.storageValue)
        }
    }

    override fun setLeftSwipeAction(action: ConversationSwipeAction) {
        updatePrefs {
            putString(AppSettingsKeys.KEY_LEFT_SWIPE_ACTION, action.storageValue)
        }
    }

    override fun setConversationDetailTextScale(scale: Float) {
        updatePrefs {
            putFloat(AppSettingsKeys.KEY_CONVERSATION_DETAIL_TEXT_SCALE, scale)
        }
    }

    override fun setRuleResourceVersion(version: String) {
        updatePrefs { putString(AppSettingsKeys.KEY_RULE_RESOURCE_VERSION, version) }
    }

    override fun setSpamModelResourceVersion(version: String) {
        updatePrefs { putString(AppSettingsKeys.KEY_SPAM_MODEL_RESOURCE_VERSION, version) }
    }

    override fun setVocabResourceVersion(version: String) {
        updatePrefs { putString(AppSettingsKeys.KEY_VOCAB_RESOURCE_VERSION, version) }
    }

    override fun setResourceUpdatedAt(timestamp: Long) {
        updatePrefs { putLong(AppSettingsKeys.KEY_RESOURCE_UPDATED_AT, timestamp) }
    }

    override fun setResourceAutoCheckEnabled(enabled: Boolean) {
        updatePrefs { putBoolean(AppSettingsKeys.KEY_RESOURCE_AUTO_CHECK_ENABLED, enabled) }
    }

    override fun setResourceAutoCheckIntervalHours(hours: Long) {
        updatePrefs {
            putLong(
                AppSettingsKeys.KEY_RESOURCE_AUTO_CHECK_INTERVAL_HOURS,
                hours.coerceAtLeast(1L)
            )
        }
    }

    override fun setResourceAutoCheckLastCheckedAt(timestamp: Long) {
        updatePrefs { putLong(AppSettingsKeys.KEY_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT, timestamp) }
    }

    override fun isSpamDetectionEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_SPAM_DETECTION_ENABLED,
            AppSettingsKeys.DEFAULT_SPAM_DETECTION_ENABLED
        )

    override fun isMuteSpamNotificationsEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_MUTE_SPAM_NOTIFICATIONS_ENABLED,
            AppSettingsKeys.DEFAULT_MUTE_SPAM_NOTIFICATIONS_ENABLED
        )

    override fun getSpamAutoAction(): SpamAutoAction =
        SpamAutoAction.fromStorageValue(
            prefs.getString(
                AppSettingsKeys.KEY_SPAM_AUTO_ACTION,
                AppSettingsKeys.DEFAULT_SPAM_AUTO_ACTION.storageValue
            )
        )

    override fun isHideFullySpamConversationsEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED,
            AppSettingsKeys.DEFAULT_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED
        )

    override fun isSmartCardEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_SMART_CARD_ENABLED,
            AppSettingsKeys.DEFAULT_SMART_CARD_ENABLED
        )

    override fun isVerificationCodeNotificationActionEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED,
            AppSettingsKeys.DEFAULT_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED
        )

    override fun isHideVerificationCodeOnLockScreenEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_HIDE_VERIFICATION_CODE_ON_LOCK_SCREEN_ENABLED,
            AppSettingsKeys.DEFAULT_HIDE_VERIFICATION_CODE_ON_LOCK_SCREEN_ENABLED
        )

    override fun getMessageTimeDisplayFormat(): MessageTimeDisplayFormat =
        MessageTimeDisplayFormat.fromStorageValue(
            prefs.getString(
                AppSettingsKeys.KEY_MESSAGE_TIME_DISPLAY_FORMAT,
                AppSettingsKeys.DEFAULT_MESSAGE_TIME_DISPLAY_FORMAT.storageValue
            )
        )

    override fun getRightSwipeAction(): ConversationSwipeAction =
        ConversationSwipeAction.fromStorageValue(
            prefs.getString(
                AppSettingsKeys.KEY_RIGHT_SWIPE_ACTION,
                AppSettingsKeys.DEFAULT_RIGHT_SWIPE_ACTION.storageValue
            )
        )

    override fun getLeftSwipeAction(): ConversationSwipeAction =
        ConversationSwipeAction.fromStorageValue(
            prefs.getString(
                AppSettingsKeys.KEY_LEFT_SWIPE_ACTION,
                AppSettingsKeys.DEFAULT_LEFT_SWIPE_ACTION.storageValue
            )
        )

    override fun getConversationDetailTextScale(): Float =
        prefs.getFloat(
            AppSettingsKeys.KEY_CONVERSATION_DETAIL_TEXT_SCALE,
            AppSettingsKeys.DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE
        )

    override fun getRuleResourceVersion(): String =
        prefs.getString(
            AppSettingsKeys.KEY_RULE_RESOURCE_VERSION,
            AppSettingsKeys.DEFAULT_RESOURCE_VERSION
        ) ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION

    override fun getSpamModelResourceVersion(): String =
        prefs.getString(
            AppSettingsKeys.KEY_SPAM_MODEL_RESOURCE_VERSION,
            AppSettingsKeys.DEFAULT_RESOURCE_VERSION
        ) ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION

    override fun getVocabResourceVersion(): String =
        prefs.getString(
            AppSettingsKeys.KEY_VOCAB_RESOURCE_VERSION,
            AppSettingsKeys.DEFAULT_RESOURCE_VERSION
        ) ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION

    override fun getResourceUpdatedAt(): Long =
        prefs.getLong(
            AppSettingsKeys.KEY_RESOURCE_UPDATED_AT,
            AppSettingsKeys.DEFAULT_RESOURCE_UPDATED_AT
        )

    override fun isResourceAutoCheckEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_RESOURCE_AUTO_CHECK_ENABLED,
            AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_ENABLED
        )

    override fun getResourceAutoCheckIntervalHours(): Long =
        prefs.getLong(
            AppSettingsKeys.KEY_RESOURCE_AUTO_CHECK_INTERVAL_HOURS,
            AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_INTERVAL_HOURS
        ).coerceAtLeast(1L)

    override fun getResourceAutoCheckLastCheckedAt(): Long =
        prefs.getLong(
            AppSettingsKeys.KEY_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT,
            AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT
        )

    private fun readSettings(): AppSettings {
        return AppSettings(
            spamDetectionEnabled = isSpamDetectionEnabled(),
            muteSpamNotificationsEnabled = isMuteSpamNotificationsEnabled(),
            spamAutoAction = getSpamAutoAction(),
            hideFullySpamConversationsEnabled = isHideFullySpamConversationsEnabled(),
            smartCardEnabled = isSmartCardEnabled(),
            verificationCodeNotificationActionEnabled =
                isVerificationCodeNotificationActionEnabled(),
            hideVerificationCodeOnLockScreenEnabled =
                isHideVerificationCodeOnLockScreenEnabled(),
            messageTimeDisplayFormat = getMessageTimeDisplayFormat(),
            rightSwipeAction = getRightSwipeAction(),
            leftSwipeAction = getLeftSwipeAction(),
            conversationDetailTextScale = getConversationDetailTextScale(),
            ruleResourceVersion = getRuleResourceVersion(),
            spamModelResourceVersion = getSpamModelResourceVersion(),
            vocabResourceVersion = getVocabResourceVersion(),
            resourceUpdatedAt = getResourceUpdatedAt(),
            resourceAutoCheckEnabled = isResourceAutoCheckEnabled(),
            resourceAutoCheckIntervalHours = getResourceAutoCheckIntervalHours(),
            resourceAutoCheckLastCheckedAt = getResourceAutoCheckLastCheckedAt()
        )
    }

    private inline fun updatePrefs(action: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = action)
        refreshSettings()
    }

    private fun refreshSettings() {
        _settings.value = readSettings()
    }
}
