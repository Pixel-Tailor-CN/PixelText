package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

class SettingsViewModel(
    private val settingsRepository: AppSettingsRepository
) : ViewModel() {
    val settings = settingsRepository.settings

    fun setSpamDetectionEnabled(enabled: Boolean) {
        settingsRepository.setSpamDetectionEnabled(enabled)
    }

    fun setMuteSpamNotificationsEnabled(enabled: Boolean) {
        settingsRepository.setMuteSpamNotificationsEnabled(enabled)
    }

    fun setHideFullySpamConversationsEnabled(enabled: Boolean) {
        settingsRepository.setHideFullySpamConversationsEnabled(enabled)
    }

    fun setSmartCardEnabled(enabled: Boolean) {
        settingsRepository.setSmartCardEnabled(enabled)
    }

    fun setVerificationCodeNotificationActionEnabled(enabled: Boolean) {
        settingsRepository.setVerificationCodeNotificationActionEnabled(enabled)
    }
}
