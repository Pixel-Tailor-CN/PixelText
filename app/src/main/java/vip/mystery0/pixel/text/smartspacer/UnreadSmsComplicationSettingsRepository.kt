package vip.mystery0.pixel.text.smartspacer

import android.content.Context
import androidx.core.content.edit

data class UnreadSmsComplicationSettings(
    val includeNormalMessages: Boolean = true,
    val includeSpamMessages: Boolean = true,
    val includeArchivedMessages: Boolean = true,
) {
    fun hasEnabledCategory(): Boolean {
        return includeNormalMessages || includeSpamMessages || includeArchivedMessages
    }
}

class UnreadSmsComplicationSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(smartspacerId: String?): UnreadSmsComplicationSettings {
        smartspacerId?.let { id ->
            readScopedSettings(id)?.let { return it }
        }
        readDefaultSettings()?.let { return it }
        return readLegacySettings()
    }

    fun setSettings(smartspacerId: String?, settings: UnreadSmsComplicationSettings) {
        prefs.edit {
            smartspacerId?.let { id ->
                putBoolean(scopedKey(id, KEY_INCLUDE_NORMAL_MESSAGES), settings.includeNormalMessages)
                putBoolean(scopedKey(id, KEY_INCLUDE_SPAM_MESSAGES), settings.includeSpamMessages)
                putBoolean(
                    scopedKey(id, KEY_INCLUDE_ARCHIVED_MESSAGES),
                    settings.includeArchivedMessages
                )
            }
            putBoolean(KEY_DEFAULT_INCLUDE_NORMAL_MESSAGES, settings.includeNormalMessages)
            putBoolean(KEY_DEFAULT_INCLUDE_SPAM_MESSAGES, settings.includeSpamMessages)
            putBoolean(KEY_DEFAULT_INCLUDE_ARCHIVED_MESSAGES, settings.includeArchivedMessages)
        }
    }

    fun removeSettings(smartspacerId: String) {
        prefs.edit {
            remove(scopedKey(smartspacerId, KEY_INCLUDE_NORMAL_MESSAGES))
            remove(scopedKey(smartspacerId, KEY_INCLUDE_SPAM_MESSAGES))
            remove(scopedKey(smartspacerId, KEY_INCLUDE_ARCHIVED_MESSAGES))
        }
    }

    private fun readScopedSettings(smartspacerId: String): UnreadSmsComplicationSettings? {
        val includeNormalKey = scopedKey(smartspacerId, KEY_INCLUDE_NORMAL_MESSAGES)
        val includeSpamKey = scopedKey(smartspacerId, KEY_INCLUDE_SPAM_MESSAGES)
        val includeArchivedKey = scopedKey(smartspacerId, KEY_INCLUDE_ARCHIVED_MESSAGES)
        if (!prefs.contains(includeNormalKey) &&
            !prefs.contains(includeSpamKey) &&
            !prefs.contains(includeArchivedKey)
        ) {
            return null
        }
        return UnreadSmsComplicationSettings(
            includeNormalMessages = prefs.getBoolean(
                includeNormalKey,
                UnreadSmsComplicationSettings().includeNormalMessages
            ),
            includeSpamMessages = prefs.getBoolean(
                includeSpamKey,
                UnreadSmsComplicationSettings().includeSpamMessages
            ),
            includeArchivedMessages = prefs.getBoolean(
                includeArchivedKey,
                UnreadSmsComplicationSettings().includeArchivedMessages
            )
        )
    }

    private fun readDefaultSettings(): UnreadSmsComplicationSettings? {
        if (!prefs.contains(KEY_DEFAULT_INCLUDE_NORMAL_MESSAGES) &&
            !prefs.contains(KEY_DEFAULT_INCLUDE_SPAM_MESSAGES) &&
            !prefs.contains(KEY_DEFAULT_INCLUDE_ARCHIVED_MESSAGES)
        ) {
            return null
        }
        return UnreadSmsComplicationSettings(
            includeNormalMessages = prefs.getBoolean(
                KEY_DEFAULT_INCLUDE_NORMAL_MESSAGES,
                UnreadSmsComplicationSettings().includeNormalMessages
            ),
            includeSpamMessages = prefs.getBoolean(
                KEY_DEFAULT_INCLUDE_SPAM_MESSAGES,
                UnreadSmsComplicationSettings().includeSpamMessages
            ),
            includeArchivedMessages = prefs.getBoolean(
                KEY_DEFAULT_INCLUDE_ARCHIVED_MESSAGES,
                UnreadSmsComplicationSettings().includeArchivedMessages
            )
        )
    }

    private fun readLegacySettings(): UnreadSmsComplicationSettings {
        return UnreadSmsComplicationSettings(
            includeNormalMessages = legacyPrefs.getBoolean(
                LEGACY_KEY_INCLUDE_NORMAL_MESSAGES,
                UnreadSmsComplicationSettings().includeNormalMessages
            ),
            includeSpamMessages = legacyPrefs.getBoolean(
                LEGACY_KEY_INCLUDE_SPAM_MESSAGES,
                UnreadSmsComplicationSettings().includeSpamMessages
            ),
            includeArchivedMessages = legacyPrefs.getBoolean(
                LEGACY_KEY_INCLUDE_ARCHIVED_MESSAGES,
                UnreadSmsComplicationSettings().includeArchivedMessages
            )
        )
    }

    private fun scopedKey(smartspacerId: String, key: String): String {
        return "unread_sms.$smartspacerId.$key"
    }

    companion object {
        private const val PREFS_NAME = "smartspacer_complication_settings"
        private const val LEGACY_PREFS_NAME = "app_settings"

        private const val KEY_INCLUDE_NORMAL_MESSAGES = "include_normal_messages"
        private const val KEY_INCLUDE_SPAM_MESSAGES = "include_spam_messages"
        private const val KEY_INCLUDE_ARCHIVED_MESSAGES = "include_archived_messages"

        private const val KEY_DEFAULT_INCLUDE_NORMAL_MESSAGES =
            "unread_sms.default.include_normal_messages"
        private const val KEY_DEFAULT_INCLUDE_SPAM_MESSAGES =
            "unread_sms.default.include_spam_messages"
        private const val KEY_DEFAULT_INCLUDE_ARCHIVED_MESSAGES =
            "unread_sms.default.include_archived_messages"

        private const val LEGACY_KEY_INCLUDE_NORMAL_MESSAGES =
            "smartspacer_unread_include_normal_messages"
        private const val LEGACY_KEY_INCLUDE_SPAM_MESSAGES =
            "smartspacer_unread_include_spam_messages"
        private const val LEGACY_KEY_INCLUDE_ARCHIVED_MESSAGES =
            "smartspacer_unread_include_archived_messages"
    }
}
