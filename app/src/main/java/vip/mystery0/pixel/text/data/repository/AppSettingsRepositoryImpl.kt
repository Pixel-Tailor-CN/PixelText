package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vip.mystery0.pixel.text.domain.settings.AppSettings
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import androidx.core.content.edit

class AppSettingsRepositoryImpl(context: Context) : AppSettingsRepository {
    private val prefs =
        context.getSharedPreferences(AppSettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _settings.value = readSettings()
        }

    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun setSpamDetectionEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(AppSettingsKeys.KEY_SPAM_DETECTION_ENABLED, enabled) }
    }

    override fun setMuteSpamNotificationsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(AppSettingsKeys.KEY_MUTE_SPAM_NOTIFICATIONS_ENABLED, enabled) }
    }

    override fun setHideFullySpamConversationsEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(AppSettingsKeys.KEY_HIDE_FULLY_SPAM_CONVERSATIONS_ENABLED, enabled)
        }
    }

    override fun setSmartCardEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(AppSettingsKeys.KEY_SMART_CARD_ENABLED, enabled) }
    }

    override fun setVerificationCodeNotificationActionEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(AppSettingsKeys.KEY_VERIFICATION_CODE_NOTIFICATION_ACTION_ENABLED, enabled)
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

    private fun readSettings(): AppSettings {
        return AppSettings(
            spamDetectionEnabled = isSpamDetectionEnabled(),
            muteSpamNotificationsEnabled = isMuteSpamNotificationsEnabled(),
            hideFullySpamConversationsEnabled = isHideFullySpamConversationsEnabled(),
            smartCardEnabled = isSmartCardEnabled(),
            verificationCodeNotificationActionEnabled =
                isVerificationCodeNotificationActionEnabled()
        )
    }
}
