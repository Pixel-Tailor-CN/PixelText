package vip.mystery0.pixel.text.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

private const val TAG = "ConversationListVM"

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
    private var conversationSubscriptionJob: Job? = null
    private var isLoading = false
    private var isRefreshingLoaded = false

    fun loadConversations(force: Boolean = false) {
        if (isLoading) return
        repository.startCacheObserving()
        if (conversationSubscriptionJob?.isActive == true) {
            if (force && !isRefreshingLoaded) {
                refreshLoaded(forceSync = false, showRefreshIndicator = false)
            }
            return
        }
        if (force && allConversations.isEmpty()) {
            _uiState.value = ConversationListUiState.Loading
        }

        loadAllConversations()
    }

    fun refreshOrLoadConversations() {
        if (conversationSubscriptionJob?.isActive != true) {
            loadConversations()
            return
        }
        if (allConversations.isEmpty()) {
            loadConversations(force = true)
        } else {
            refreshSilent()
        }
    }

    fun refreshConversations() {
        if (isLoading || isRefreshingLoaded) return
        refreshLoaded(forceSync = true, showRefreshIndicator = true)
    }

    private fun loadAllConversations() {
        isLoading = true
        conversationSubscriptionJob = viewModelScope.launch {
            var retryDelayMillis = INITIAL_SUBSCRIPTION_RETRY_DELAY_MILLIS
            while (true) {
                try {
                    isLoading = allConversations.isEmpty()
                    if (!repository.isCacheReady()) {
                        _isSyncing.value = true
                    }
                    repository.getAllConversations()
                        .collect { conversations ->
                            retryDelayMillis = INITIAL_SUBSCRIPTION_RETRY_DELAY_MILLIS
                            _isSyncing.value = false
                            replaceConversations(conversations)
                            isLoading = false
                        }
                    throw IllegalStateException("conversation flow completed")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "conversation subscription failed error=${error::class.java.simpleName}",
                        error,
                    )
                    if (allConversations.isEmpty()) {
                        _uiState.value =
                            ConversationListUiState.Error(error.message ?: "Unknown error")
                    }
                } finally {
                    _isSyncing.value = false
                    isLoading = false
                }

                delay(retryDelayMillis)
                retryDelayMillis =
                    (retryDelayMillis * 2).coerceAtMost(MAX_SUBSCRIPTION_RETRY_DELAY_MILLIS)
            }
        }
    }

    fun refreshSilent() {
        if (isLoading || isRefreshingLoaded || allConversations.isEmpty()) return
        refreshLoaded(forceSync = false, showRefreshIndicator = false)
    }

    private fun refreshLoaded(forceSync: Boolean, showRefreshIndicator: Boolean) {
        isRefreshingLoaded = true
        if (showRefreshIndicator) {
            _isRefreshing.value = true
        }
        viewModelScope.launch {
            try {
                if (forceSync) {
                    repository.forceSyncConversations()
                }
                val newList = repository.getAllConversations().first()
                replaceConversations(newList)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (showRefreshIndicator) {
                    Log.e(
                        TAG,
                        "conversation refresh failed error=${error::class.java.simpleName}",
                        error,
                    )
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

    private companion object {
        const val INITIAL_SUBSCRIPTION_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_SUBSCRIPTION_RETRY_DELAY_MILLIS = 30_000L
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
