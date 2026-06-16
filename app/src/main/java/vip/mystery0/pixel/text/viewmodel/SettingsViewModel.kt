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
import vip.mystery0.pixel.text.domain.settings.SpamAutoAction

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

    fun setSpamAutoAction(action: SpamAutoAction) {
        settingsRepository.setSpamAutoAction(action)
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

    fun setHideVerificationCodeOnLockScreenEnabled(enabled: Boolean) {
        settingsRepository.setHideVerificationCodeOnLockScreenEnabled(enabled)
    }

    fun checkResourceUpdates() {
        if (_resourceUpdateState.value is ResourceUpdateState.Busy) return
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
                        _resourceUpdateState.value =
                            ResourceUpdateState.NoUpdate("当前规则和模型已经是最新版本")
                    } else {
                        pendingManifest = manifest
                        _resourceUpdateState.value =
                            ResourceUpdateState.Available(manifest.toResourceUpdateDetail())
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
        if (_resourceUpdateState.value is ResourceUpdateState.Busy) return
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

    fun deleteDownloadedModelResource() {
        runResourceMaintenance(
            busyState = ResourceUpdateState.Working("正在删除下载的模型文件"),
            successMessage = "已删除下载的模型文件，已回退到内置模型",
            action = hubResourceRepository::deleteDownloadedModel
        )
    }

    fun deleteDownloadedRulesResource() {
        runResourceMaintenance(
            busyState = ResourceUpdateState.Working("正在删除下载的智能卡片规则文件"),
            successMessage = "已删除下载的智能卡片规则文件，已回退到内置规则",
            action = hubResourceRepository::deleteDownloadedRules
        )
    }

    fun dismissResourceUpdateDialog() {
        if (
            _resourceUpdateState.value is ResourceUpdateState.Available ||
            _resourceUpdateState.value is ResourceUpdateState.NoUpdate
        ) {
            pendingManifest = null
            _resourceUpdateState.value = ResourceUpdateState.Idle
        }
    }

    private fun runResourceMaintenance(
        busyState: ResourceUpdateState.Working,
        successMessage: String,
        action: suspend () -> HubOperationResult,
    ) {
        if (_resourceUpdateState.value is ResourceUpdateState.Busy) return
        pendingManifest = null
        _resourceUpdateState.value = busyState
        viewModelScope.launch {
            when (val result = action()) {
                HubOperationResult.Success -> {
                    _resourceUpdateState.value = ResourceUpdateState.Success(successMessage)
                }

                is HubOperationResult.Failure -> {
                    _resourceUpdateState.value = ResourceUpdateState.Error(result.message)
                }
            }
        }
    }
}

data class ResourceUpdateDetail(
    val modelVersion: String,
    val modelSizeBytes: Long?,
    val ruleVersion: String,
    val ruleSizeBytes: Long?,
    val releaseNotes: String,
)

sealed interface ResourceUpdateState {
    sealed interface Busy : ResourceUpdateState

    data object Idle : ResourceUpdateState
    data object Checking : ResourceUpdateState, Busy
    data class Available(val detail: ResourceUpdateDetail) : ResourceUpdateState
    data class NoUpdate(val message: String) : ResourceUpdateState
    data object Updating : ResourceUpdateState, Busy
    data class Working(val message: String) : ResourceUpdateState, Busy
    data class Success(val message: String) : ResourceUpdateState
    data class Error(val message: String) : ResourceUpdateState
}

private fun HubResourceManifest.toResourceUpdateDetail(): ResourceUpdateDetail {
    val notes = listOfNotNull(
        spamModel?.releaseNotes
            ?.takeIf { it.isNotBlank() }
            ?.let { "模型：$it" },
        rules?.releaseNotes
            ?.takeIf { it.isNotBlank() }
            ?.let { "规则：$it" }
    ).joinToString(separator = "\n")

    return ResourceUpdateDetail(
        modelVersion = spamModel?.version ?: "未提供",
        modelSizeBytes = spamModel?.model?.sizeBytes,
        ruleVersion = rules?.version ?: "未提供",
        ruleSizeBytes = rules?.sizeBytes,
        releaseNotes = notes.ifBlank { "暂无版本说明" }
    )
}
