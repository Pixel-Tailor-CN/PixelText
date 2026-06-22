package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

class ConversationListViewModel(
    private val repository: MessageRepository,
    settingsRepository: AppSettingsRepository
) : ViewModel() {
    private val _uiState =
        MutableStateFlow<ConversationListUiState>(ConversationListUiState.Loading)
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()
    val settings = settingsRepository.settings

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _markAllReadProgress = MutableStateFlow<MarkAllReadProgress?>(null)
    val markAllReadProgress: StateFlow<MarkAllReadProgress?> =
        _markAllReadProgress.asStateFlow()

    private val _markAllReadResultEvents =
        Channel<MarkAllReadResultEvent>(Channel.BUFFERED)
    val markAllReadResultEvents = _markAllReadResultEvents.receiveAsFlow()

    private val allConversations = mutableListOf<ConversationModel>()
    private val pendingDeletedThreadIds = mutableSetOf<Long>()
    private val pendingArchivedThreadIds = mutableSetOf<Long>()
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
            markLoadedConversationsAsRead(setOf(threadId))
            refreshSilent()
        }
    }

    fun toggleRead(conversation: ConversationModel) {
        viewModelScope.launch {
            if (conversation.unreadCount > 0) {
                repository.markThreadAsRead(conversation.threadId)
                markLoadedConversationsAsRead(setOf(conversation.threadId))
            } else {
                repository.markThreadAsUnread(conversation.threadId)
                markLoadedConversationAsUnread(conversation.threadId)
            }
            refreshSilent()
        }
    }

    fun markAllConversationsAsRead() {
        if (_markAllReadProgress.value != null) return

        val unreadThreadIds = allConversations
            .filter { it.unreadCount > 0 }
            .map { it.threadId }
            .toSet()

        if (unreadThreadIds.isEmpty()) {
            _markAllReadResultEvents.trySend(MarkAllReadResultEvent.NoUnread)
            return
        }

        _markAllReadProgress.value =
            MarkAllReadProgress(completed = 0, total = unreadThreadIds.size)
        viewModelScope.launch {
            val completedThreadIds = mutableSetOf<Long>()
            runCatching {
                unreadThreadIds.forEach { threadId ->
                    repository.markThreadAsRead(threadId)
                    completedThreadIds += threadId
                    _markAllReadProgress.value = MarkAllReadProgress(
                        completed = completedThreadIds.size,
                        total = unreadThreadIds.size
                    )
                }
            }.onSuccess {
                markLoadedConversationsAsRead(completedThreadIds)
                _markAllReadResultEvents.trySend(
                    MarkAllReadResultEvent.Success(completedThreadIds.size)
                )
                refreshSilent()
            }.onFailure { e ->
                markLoadedConversationsAsRead(completedThreadIds)
                _markAllReadResultEvents.trySend(
                    MarkAllReadResultEvent.Failure(e.message ?: "标记失败")
                )
            }
            _markAllReadProgress.value = null
        }
    }

    fun archiveSelected(conversations: List<ConversationModel>) {
        if (conversations.isEmpty()) return
        viewModelScope.launch {
            repository.archiveConversations(conversations)
            loadConversations(force = true)
        }
    }

    fun archiveConversation(conversation: ConversationModel) {
        pendingArchivedThreadIds += conversation.threadId
        removeLoadedConversations(setOf(conversation.threadId))
        viewModelScope.launch {
            runCatching {
                repository.archiveConversations(listOf(conversation))
            }.onSuccess {
                pendingArchivedThreadIds -= conversation.threadId
                refreshSilent()
            }.onFailure {
                pendingArchivedThreadIds -= conversation.threadId
                restoreLoadedConversation(conversation)
            }
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
        removeLoadedConversations(threadIds)
    }

    fun restorePendingDelete(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        pendingDeletedThreadIds.removeAll(threadIds)
        refreshSilent()
    }

    private fun replaceConversations(conversations: List<ConversationModel>) {
        val visibleList = conversations
            .filterNot {
                it.threadId in pendingDeletedThreadIds ||
                        it.threadId in pendingArchivedThreadIds
            }
            .distinctBy { it.threadId }
        allConversations.clear()
        allConversations.addAll(visibleList)
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private fun removeLoadedConversations(threadIds: Set<Long>) {
        allConversations.removeAll { it.threadId in threadIds }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private fun restoreLoadedConversation(conversation: ConversationModel) {
        allConversations.removeAll { it.threadId == conversation.threadId }
        allConversations += conversation
        allConversations.sortByDescending { it.timestamp }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private fun markLoadedConversationsAsRead(threadIds: Set<Long>) {
        allConversations.replaceAll { conversation ->
            if (conversation.threadId in threadIds) conversation.copy(unreadCount = 0)
            else conversation
        }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private fun markLoadedConversationAsUnread(threadId: Long) {
        allConversations.replaceAll { conversation ->
            if (conversation.threadId == threadId) {
                conversation.copy(unreadCount = conversation.unreadCount.coerceAtLeast(1))
            } else {
                conversation
            }
        }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }
}

sealed class ConversationListUiState {
    data object Loading : ConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) : ConversationListUiState()
    data class Error(val message: String) : ConversationListUiState()
}

sealed interface MarkAllReadResultEvent {
    data class Success(val conversationCount: Int) : MarkAllReadResultEvent
    data object NoUnread : MarkAllReadResultEvent
    data class Failure(val reason: String) : MarkAllReadResultEvent
}

data class MarkAllReadProgress(
    val completed: Int,
    val total: Int
) {
    val percent: Int
        get() = if (total <= 0) 0 else (completed * 100 / total).coerceIn(0, 100)
}
