package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository

class ConversationListViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _uiState =
        MutableStateFlow<ConversationListUiState>(ConversationListUiState.Loading)
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val allConversations = mutableListOf<ConversationModel>()
    private val pendingDeletedThreadIds = mutableSetOf<Long>()
    private var isLoading = false
    private var isRefreshingLoaded = false

    fun loadConversations(force: Boolean = false) {
        if (isLoading) return
        repository.startCacheObserving()
        if (force) {
            allConversations.clear()
            _uiState.value = ConversationListUiState.Loading
        } else if (allConversations.isNotEmpty()) {
            return
        }

        loadAllConversations()
    }

    fun refreshOrLoadConversations() {
        if (allConversations.isEmpty()) {
            loadConversations(force = true)
        } else {
            refreshSilent()
        }
    }

    fun refreshConversations() {
        if (isLoading || isRefreshingLoaded) return
        if (allConversations.isEmpty()) {
            loadConversations(force = true)
        } else {
            refreshLoaded(showRefreshIndicator = true)
        }
    }

    private fun loadAllConversations() {
        isLoading = true
        viewModelScope.launch {
            if (!repository.isCacheReady()) {
                _isSyncing.value = true
            }
            repository.getAllConversations()
                .catch { e ->
                    _isSyncing.value = false
                    if (allConversations.isEmpty()) {
                        _uiState.value = ConversationListUiState.Error(e.message ?: "Unknown error")
                    }
                    isLoading = false
                }
                .collect { conversations ->
                    _isSyncing.value = false
                    replaceConversations(conversations)
                    isLoading = false
                }
        }
    }

    fun refreshSilent() {
        if (isLoading || isRefreshingLoaded || allConversations.isEmpty()) return
        refreshLoaded(showRefreshIndicator = false)
    }

    private fun refreshLoaded(showRefreshIndicator: Boolean) {
        isRefreshingLoaded = true
        if (showRefreshIndicator) {
            _isRefreshing.value = true
        }
        viewModelScope.launch {
            try {
                repository.getAllConversations()
                    .catch { }
                    .collect { newList ->
                        replaceConversations(newList)
                    }
            } finally {
                if (showRefreshIndicator) {
                    _isRefreshing.value = false
                }
                isRefreshingLoaded = false
            }
        }
    }

    fun markAsRead(threadId: Long) {
        viewModelScope.launch {
            repository.markThreadAsRead(threadId)
        }
    }

    fun archiveSelected(conversations: List<ConversationModel>) {
        if (conversations.isEmpty()) return
        viewModelScope.launch {
            repository.archiveConversations(conversations)
            loadConversations(force = true)
        }
    }

    fun deleteSelected(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        viewModelScope.launch {
            repository.deleteThreads(threadIds)
            pendingDeletedThreadIds.removeAll(threadIds)
            refreshSilent()
        }
    }

    fun hidePendingDelete(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        pendingDeletedThreadIds.addAll(threadIds)
        allConversations.removeAll { it.threadId in threadIds }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    fun restorePendingDelete(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        pendingDeletedThreadIds.removeAll(threadIds)
        refreshSilent()
    }

    private fun replaceConversations(conversations: List<ConversationModel>) {
        val visibleList = conversations
            .filterNot { it.threadId in pendingDeletedThreadIds }
            .distinctBy { it.threadId }
        allConversations.clear()
        allConversations.addAll(visibleList)
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }
}

sealed class ConversationListUiState {
    data object Loading : ConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) : ConversationListUiState()
    data class Error(val message: String) : ConversationListUiState()
}
