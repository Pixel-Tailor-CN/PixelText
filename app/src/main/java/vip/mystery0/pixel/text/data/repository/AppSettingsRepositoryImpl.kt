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
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionConfig
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionType
import vip.mystery0.pixel.text.domain.settings.SpamAutoAction
import vip.mystery0.pixel.text.domain.settings.SmsNotificationIcon
import vip.mystery0.pixel.text.domain.settings.defaultLabelTemplate
import vip.mystery0.pixel.text.domain.settings.defaultOrder
import vip.mystery0.pixel.text.domain.settings.normalizeNotificationQuickActionConfigs
import vip.mystery0.pixel.text.domain.settings.preferenceLabelKey
import vip.mystery0.pixel.text.domain.settings.preferenceOrderKey

class AppSettingsRepositoryImpl(context: Context) : AppSettingsRepository {
    private val prefs =
        context.getSharedPreferences(AppSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateSpamIsolationSetting()
    }

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

    override fun setSpamIsolationEnabled(enabled: Boolean) {
        updatePrefs { putBoolean(AppSettingsKeys.KEY_SPAM_ISOLATION_ENABLED, enabled) }
    }

    override fun setShowSpamContentByDefault(enabled: Boolean) {
        updatePrefs { putBoolean(AppSettingsKeys.KEY_SHOW_SPAM_CONTENT_BY_DEFAULT, enabled) }
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

    override fun setShowVerificationCodeContentByDefault(enabled: Boolean) {
        updatePrefs {
            putBoolean(
                AppSettingsKeys.KEY_SHOW_VERIFICATION_CODE_CONTENT_BY_DEFAULT,
                enabled,
            )
        }
    }

    override fun setVerificationCodeAutoDeleteEnabled(enabled: Boolean) {
        updatePrefs {
            putBoolean(AppSettingsKeys.KEY_VERIFICATION_CODE_AUTO_DELETE_ENABLED, enabled)
        }
    }

    override fun setVerificationCodeRetentionDays(days: Int) {
        updatePrefs {
            putInt(
                AppSettingsKeys.KEY_VERIFICATION_CODE_RETENTION_DAYS,
                days.coerceIn(
                    AppSettingsKeys.MIN_VERIFICATION_CODE_RETENTION_DAYS,
                    AppSettingsKeys.MAX_VERIFICATION_CODE_RETENTION_DAYS,
                ),
            )
        }
    }

    override fun setUnreadBadgeEnabled(enabled: Boolean) {
        updatePrefs { putBoolean(AppSettingsKeys.KEY_UNREAD_BADGE_ENABLED, enabled) }
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

    override fun setNotificationQuickActionConfigs(configs: List<NotificationQuickActionConfig>) {
        val normalizedConfigs = configs.normalizeNotificationQuickActionConfigs()
        updatePrefs {
            normalizedConfigs.forEach { config ->
                putString(config.type.preferenceLabelKey(), config.labelTemplate.trim())
                putInt(config.type.preferenceOrderKey(), config.order)
            }
        }
    }

    override fun setSmsNotificationIconId(iconId: String) {
        val normalizedId = SmsNotificationIcon.fromId(iconId).storageId
        updatePrefs {
            putString(AppSettingsKeys.KEY_SMS_NOTIFICATION_ICON_ID, normalizedId)
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

    override fun setSampleSubmissionShortcutHintShown(shown: Boolean) {
        updatePrefs {
            putBoolean(AppSettingsKeys.KEY_SAMPLE_SUBMISSION_SHORTCUT_HINT_SHOWN, shown)
        }
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

    override fun isSpamIsolationEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_SPAM_ISOLATION_ENABLED,
            AppSettingsKeys.DEFAULT_SPAM_ISOLATION_ENABLED
        )

    override fun isShowSpamContentByDefault(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_SHOW_SPAM_CONTENT_BY_DEFAULT,
            AppSettingsKeys.DEFAULT_SHOW_SPAM_CONTENT_BY_DEFAULT
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

    override fun isShowVerificationCodeContentByDefault(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_SHOW_VERIFICATION_CODE_CONTENT_BY_DEFAULT,
            AppSettingsKeys.DEFAULT_SHOW_VERIFICATION_CODE_CONTENT_BY_DEFAULT,
        )

    override fun isVerificationCodeAutoDeleteEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_VERIFICATION_CODE_AUTO_DELETE_ENABLED,
            AppSettingsKeys.DEFAULT_VERIFICATION_CODE_AUTO_DELETE_ENABLED,
        )

    override fun getVerificationCodeRetentionDays(): Int =
        prefs.getInt(
            AppSettingsKeys.KEY_VERIFICATION_CODE_RETENTION_DAYS,
            AppSettingsKeys.DEFAULT_VERIFICATION_CODE_RETENTION_DAYS,
        ).coerceIn(
            AppSettingsKeys.MIN_VERIFICATION_CODE_RETENTION_DAYS,
            AppSettingsKeys.MAX_VERIFICATION_CODE_RETENTION_DAYS,
        )

    override fun isUnreadBadgeEnabled(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_UNREAD_BADGE_ENABLED,
            AppSettingsKeys.DEFAULT_UNREAD_BADGE_ENABLED,
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

    override fun getNotificationQuickActionConfigs(): List<NotificationQuickActionConfig> {
        return NotificationQuickActionType.entries.map { type ->
            NotificationQuickActionConfig(
                type = type,
                labelTemplate = prefs.getString(
                    type.preferenceLabelKey(),
                    type.defaultLabelTemplate()
                )
                    ?: type.defaultLabelTemplate(),
                order = prefs.getInt(type.preferenceOrderKey(), type.defaultOrder())
            )
        }.normalizeNotificationQuickActionConfigs()
    }

    override fun getSmsNotificationIconId(): String =
        SmsNotificationIcon.fromId(
            prefs.getString(
                AppSettingsKeys.KEY_SMS_NOTIFICATION_ICON_ID,
                AppSettingsKeys.DEFAULT_SMS_NOTIFICATION_ICON_ID
            )
        ).storageId

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

    override fun isSampleSubmissionShortcutHintShown(): Boolean =
        prefs.getBoolean(
            AppSettingsKeys.KEY_SAMPLE_SUBMISSION_SHORTCUT_HINT_SHOWN,
            AppSettingsKeys.DEFAULT_SAMPLE_SUBMISSION_SHORTCUT_HINT_SHOWN
        )

    private fun readSettings(): AppSettings {
        return AppSettings(
            spamDetectionEnabled = isSpamDetectionEnabled(),
            muteSpamNotificationsEnabled = isMuteSpamNotificationsEnabled(),
            spamAutoAction = getSpamAutoAction(),
            spamIsolationEnabled = isSpamIsolationEnabled(),
            showSpamContentByDefault = isShowSpamContentByDefault(),
            smartCardEnabled = isSmartCardEnabled(),
            verificationCodeNotificationActionEnabled =
                isVerificationCodeNotificationActionEnabled(),
            hideVerificationCodeOnLockScreenEnabled =
                isHideVerificationCodeOnLockScreenEnabled(),
            showVerificationCodeContentByDefault =
                isShowVerificationCodeContentByDefault(),
            verificationCodeAutoDeleteEnabled = isVerificationCodeAutoDeleteEnabled(),
            verificationCodeRetentionDays = getVerificationCodeRetentionDays(),
            unreadBadgeEnabled = isUnreadBadgeEnabled(),
            messageTimeDisplayFormat = getMessageTimeDisplayFormat(),
            rightSwipeAction = getRightSwipeAction(),
            leftSwipeAction = getLeftSwipeAction(),
            notificationQuickActionConfigs = getNotificationQuickActionConfigs(),
            smsNotificationIconId = getSmsNotificationIconId(),
            ruleResourceVersion = getRuleResourceVersion(),
            spamModelResourceVersion = getSpamModelResourceVersion(),
            vocabResourceVersion = getVocabResourceVersion(),
            resourceUpdatedAt = getResourceUpdatedAt(),
            resourceAutoCheckEnabled = isResourceAutoCheckEnabled(),
            resourceAutoCheckIntervalHours = getResourceAutoCheckIntervalHours(),
            resourceAutoCheckLastCheckedAt = getResourceAutoCheckLastCheckedAt(),
            sampleSubmissionShortcutHintShown = isSampleSubmissionShortcutHintShown()
        )
    }

    private fun migrateSpamIsolationSetting() {
        if (prefs.contains(AppSettingsKeys.KEY_SPAM_ISOLATION_ENABLED)) return
        if (!prefs.contains(AppSettingsKeys.KEY_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED)) return

        val legacyValue = prefs.getBoolean(
            AppSettingsKeys.KEY_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED,
            AppSettingsKeys.DEFAULT_SPAM_ISOLATION_ENABLED,
        )
        prefs.edit {
            putBoolean(AppSettingsKeys.KEY_SPAM_ISOLATION_ENABLED, legacyValue)
            remove(AppSettingsKeys.KEY_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED)
        }
    }

    private inline fun updatePrefs(action: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = action)
        refreshSettings()
    }

    private fun refreshSettings() {
        _settings.value = readSettings()
    }
}
