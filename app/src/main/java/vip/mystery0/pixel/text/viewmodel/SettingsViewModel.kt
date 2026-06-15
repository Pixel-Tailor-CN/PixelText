package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.repository.HubResourceRepository
import vip.mystery0.pixel.text.domain.hub.HubOperationResult
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

class SettingsViewModel(
    private val settingsRepository: AppSettingsRepository,
    private val hubResourceRepository: HubResourceRepository,
) : ViewModel() {
    val settings = settingsRepository.settings
    private val _resourceUpdateState =
        MutableStateFlow<ResourceUpdateState>(ResourceUpdateState.Idle)
    val resourceUpdateState: StateFlow<ResourceUpdateState> =
        _resourceUpdateState.asStateFlow()
    private var pendingManifest: HubResourceManifest? = null

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

    fun checkResourceUpdates() {
        if (_resourceUpdateState.value is ResourceUpdateState.Checking) return
        _resourceUpdateState.value = ResourceUpdateState.Checking
        viewModelScope.launch {
            runCatching { hubResourceRepository.checkManifest() }
                .onSuccess { manifest ->
                    val remoteRuleVersion =
                        manifest.rules?.version ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION
                    val remoteModelVersion =
                        manifest.spamModel?.version ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION
                    val currentRuleVersion = settingsRepository.getRuleResourceVersion()
                    val currentModelVersion = settingsRepository.getSpamModelResourceVersion()
                    if (
                        remoteRuleVersion == currentRuleVersion &&
                        remoteModelVersion == currentModelVersion
                    ) {
                        pendingManifest = null
                        _resourceUpdateState.value = ResourceUpdateState.Success("无更新版本")
                    } else {
                        pendingManifest = manifest
                        _resourceUpdateState.value =
                            ResourceUpdateState.Available(
                                "规则 $remoteRuleVersion，模型 $remoteModelVersion"
                            )
                    }
                }
                .onFailure { error ->
                    _resourceUpdateState.value =
                        ResourceUpdateState.Error(error.message ?: "检查更新失败")
                }
        }
    }

    fun installResourceUpdates() {
        val manifest = pendingManifest ?: return
        if (_resourceUpdateState.value is ResourceUpdateState.Updating) return
        _resourceUpdateState.value = ResourceUpdateState.Updating
        viewModelScope.launch {
            when (val result = hubResourceRepository.updateAll(manifest)) {
                HubOperationResult.Success -> {
                    pendingManifest = null
                    _resourceUpdateState.value = ResourceUpdateState.Success("资源已更新")
                }

                is HubOperationResult.Failure -> {
                    _resourceUpdateState.value = ResourceUpdateState.Error(result.message)
                }
            }
        }
    }

    fun dismissResourceUpdateDialog() {
        if (_resourceUpdateState.value is ResourceUpdateState.Available) {
            pendingManifest = null
            _resourceUpdateState.value = ResourceUpdateState.Idle
        }
    }
}

sealed interface ResourceUpdateState {
    data object Idle : ResourceUpdateState
    data object Checking : ResourceUpdateState
    data class Available(val summary: String) : ResourceUpdateState
    data object Updating : ResourceUpdateState
    data class Success(val message: String) : ResourceUpdateState
    data class Error(val message: String) : ResourceUpdateState
}
