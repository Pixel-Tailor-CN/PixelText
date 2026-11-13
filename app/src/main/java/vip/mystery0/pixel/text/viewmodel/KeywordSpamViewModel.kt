package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.BlockedKeyword
import vip.mystery0.pixel.text.domain.model.KeywordSaveResult
import vip.mystery0.pixel.text.domain.spam.KeywordSpamRepository
import vip.mystery0.pixel.text.util.AppStrings
import vip.mystery0.pixel.text.worker.KeywordSpamRebuildScheduler

class KeywordSpamViewModel(
    private val repository: KeywordSpamRepository,
    private val scheduler: KeywordSpamRebuildScheduler,
) : ViewModel() {
    val keywords: StateFlow<List<BlockedKeyword>> = repository.observeKeywords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rebuildState: StateFlow<KeywordRebuildUiState> = scheduler.observeState()
        .map { state ->
            when (state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED -> KeywordRebuildUiState.Running

                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED -> KeywordRebuildUiState.Failed

                else -> KeywordRebuildUiState.Idle
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeywordRebuildUiState.Idle)

    private val _saveState = MutableStateFlow<KeywordSaveUiState>(KeywordSaveUiState.Idle)
    val saveState: StateFlow<KeywordSaveUiState> = _saveState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun save(id: Long?, value: String) {
        if (_saveState.value == KeywordSaveUiState.Saving) return
        _saveState.value = KeywordSaveUiState.Saving
        viewModelScope.launch {
            runCatching { repository.save(id, value) }
                .onSuccess { result ->
                    _saveState.value = when (result) {
                        is KeywordSaveResult.Success -> {
                            scheduler.schedule()
                            KeywordSaveUiState.Saved
                        }

                        KeywordSaveResult.Empty ->
                            KeywordSaveUiState.Error(AppStrings.get(R.string.sys_keyword_empty))

                        KeywordSaveResult.Duplicate ->
                            KeywordSaveUiState.Error(AppStrings.get(R.string.sys_keyword_duplicate))

                        KeywordSaveResult.NotFound ->
                            KeywordSaveUiState.Error(AppStrings.get(R.string.sys_keyword_not_found))
                    }
                }
                .onFailure { error ->
                    _saveState.value = KeywordSaveUiState.Error(
                        error.message ?: AppStrings.get(R.string.sys_keyword_save_failed)
                    )
                }
        }
    }

    fun delete(keyword: BlockedKeyword) {
        viewModelScope.launch {
            runCatching { repository.delete(keyword.id) }
                .onSuccess { deleted ->
                    if (deleted) {
                        scheduler.schedule()
                        _message.value = AppStrings.get(R.string.sys_keyword_deleted, keyword.keyword)
                    } else {
                        _message.value = AppStrings.get(R.string.sys_keyword_not_found)
                    }
                }
                .onFailure { error ->
                    _message.value = error.message ?: AppStrings.get(R.string.sys_keyword_delete_failed)
                }
        }
    }

    fun retryRebuild() {
        scheduler.schedule()
    }

    fun resetSaveState() {
        _saveState.value = KeywordSaveUiState.Idle
    }

    fun consumeMessage() {
        _message.value = null
    }
}

sealed interface KeywordSaveUiState {
    data object Idle : KeywordSaveUiState
    data object Saving : KeywordSaveUiState
    data object Saved : KeywordSaveUiState
    data class Error(val message: String) : KeywordSaveUiState
}

enum class KeywordRebuildUiState {
    Idle,
    Running,
    Failed,
}
