package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.repository.HubResourceRepository
import vip.mystery0.pixel.text.data.resource.BundledResourceVersionProvider
import vip.mystery0.pixel.text.domain.hub.HubOperationResult
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateAvailability
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateDetail
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.settings.ConversationSwipeAction
import vip.mystery0.pixel.text.domain.settings.MessageTimeDisplayFormat
import vip.mystery0.pixel.text.domain.settings.SpamAutoAction
import vip.mystery0.pixel.text.domain.settings.formatResourceVersionForDisplay
import vip.mystery0.pixel.text.worker.ResourceUpdateScheduler

class SettingsViewModel(
    private val settingsRepository: AppSettingsRepository,
    private val hubResourceRepository: HubResourceRepository,
    private val messageRepository: MessageRepository,
    private val resourceUpdateScheduler: ResourceUpdateScheduler,
    bundledResourceVersionProvider: BundledResourceVersionProvider,
) : ViewModel() {
    val settings = settingsRepository.settings
    private val bundledResourceVersions = bundledResourceVersionProvider.read()
    private val _resourceUpdateState =
        MutableStateFlow<ResourceUpdateState>(ResourceUpdateState.Idle)
    val resourceUpdateState: StateFlow<ResourceUpdateState> =
        _resourceUpdateState.asStateFlow()
    private val _smsSyncState = MutableStateFlow<SmsSyncState>(SmsSyncState.Idle)
    val smsSyncState: StateFlow<SmsSyncState> = _smsSyncState.asStateFlow()
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

    fun setMessageTimeDisplayFormat(format: MessageTimeDisplayFormat) {
        settingsRepository.setMessageTimeDisplayFormat(format)
    }

    fun setRightSwipeAction(action: ConversationSwipeAction) {
        settingsRepository.setRightSwipeAction(action)
    }

    fun setLeftSwipeAction(action: ConversationSwipeAction) {
        settingsRepository.setLeftSwipeAction(action)
    }

    fun setResourceAutoCheckEnabled(enabled: Boolean) {
        settingsRepository.setResourceAutoCheckEnabled(enabled)
        if (enabled) {
            settingsRepository.setResourceAutoCheckLastCheckedAt(System.currentTimeMillis())
        }
        resourceUpdateScheduler.syncAfterSettingsChange()
    }

    fun setResourceAutoCheckIntervalHours(hours: Long): Boolean {
        if (hours <= 0L) return false
        settingsRepository.setResourceAutoCheckIntervalHours(hours)
        if (settingsRepository.isResourceAutoCheckEnabled()) {
            settingsRepository.setResourceAutoCheckLastCheckedAt(System.currentTimeMillis())
        }
        resourceUpdateScheduler.syncAfterSettingsChange()
        return true
    }

    fun displayRuleResourceVersion(version: String): String {
        return formatResourceVersionForDisplay(version, bundledResourceVersions.rulesVersion)
    }

    fun displaySpamModelResourceVersion(version: String): String {
        return formatResourceVersionForDisplay(version, bundledResourceVersions.spamModelVersion)
    }

    fun forceSyncSmsData() {
        if (_smsSyncState.value is SmsSyncState.Syncing) return
        _smsSyncState.value = SmsSyncState.Syncing
        viewModelScope.launch {
            runCatching { messageRepository.forceSyncConversations() }
                .onSuccess {
                    _smsSyncState.value = SmsSyncState.Idle
                }
                .onFailure { error ->
                    _smsSyncState.value =
                        SmsSyncState.Error(error.message ?: "同步短信数据失败")
                }
        }
    }

    fun dismissSmsSyncError() {
        if (_smsSyncState.value is SmsSyncState.Error) {
            _smsSyncState.value = SmsSyncState.Idle
        }
    }

    fun checkResourceUpdates() {
        if (_resourceUpdateState.value is ResourceUpdateState.Busy) return
        _resourceUpdateState.value = ResourceUpdateState.Checking
        viewModelScope.launch {
            runCatching { hubResourceRepository.checkResourceUpdateAvailability() }
                .onSuccess { result ->
                    when (result) {
                        is ResourceUpdateAvailability.Available -> {
                            pendingManifest = result.manifest
                            _resourceUpdateState.value =
                                ResourceUpdateState.Available(result.detail)
                        }

                        is ResourceUpdateAvailability.NoUpdate -> {
                            pendingManifest = null
                            _resourceUpdateState.value =
                                ResourceUpdateState.NoUpdate(result.message)
                        }
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
        _resourceUpdateState.value = ResourceUpdateState.Updating(
            message = "正在准备下载资源",
            progress = 0f
        )
        viewModelScope.launch {
            val result = hubResourceRepository.updateAll(manifest) { message, progress ->
                _resourceUpdateState.value = ResourceUpdateState.Updating(
                    message = message,
                    progress = progress
                )
            }
            when (result) {
                HubOperationResult.Success -> {
                    pendingManifest = null
                    _resourceUpdateState.value = ResourceUpdateState.Updating(
                        message = "资源更新完成",
                        progress = 1f
                    )
                    _resourceUpdateState.value = ResourceUpdateState.Success("资源已更新")
                }

                is HubOperationResult.Failure -> {
                    pendingManifest = null
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
        when (_resourceUpdateState.value) {
            ResourceUpdateState.Idle,
            is ResourceUpdateState.Busy -> Unit

            else -> {
                pendingManifest = null
                _resourceUpdateState.value = ResourceUpdateState.Idle
            }
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

sealed interface ResourceUpdateState {
    sealed interface Busy : ResourceUpdateState

    data object Idle : ResourceUpdateState
    data object Checking : ResourceUpdateState, Busy
    data class Available(val detail: ResourceUpdateDetail) : ResourceUpdateState
    data class NoUpdate(val message: String) : ResourceUpdateState
    data class Updating(
        val message: String,
        val progress: Float,
    ) : ResourceUpdateState, Busy
    data class Working(val message: String) : ResourceUpdateState, Busy
    data class Success(val message: String) : ResourceUpdateState
    data class Error(val message: String) : ResourceUpdateState
}

sealed interface SmsSyncState {
    data object Idle : SmsSyncState
    data object Syncing : SmsSyncState
    data class Error(val message: String) : SmsSyncState
}
