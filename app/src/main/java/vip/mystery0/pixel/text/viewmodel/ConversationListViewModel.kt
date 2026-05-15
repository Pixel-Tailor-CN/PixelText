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

    private val allConversations = mutableListOf<ConversationModel>()
    private val pendingDeletedThreadIds = mutableSetOf<Long>()
    private var offset = 0
    private var isLoadingMore = false
    private var hasMore = true

    fun loadConversations(force: Boolean = false) {
        if (force) {
            offset = 0
            allConversations.clear()
            hasMore = true
            _uiState.value = ConversationListUiState.Loading
        } else if (allConversations.isNotEmpty()) {
            return
        }

        fetchNextBatch(100)
    }

    fun loadMore() {
        if (isLoadingMore || !hasMore) return
        fetchNextBatch(50)
    }

    private fun fetchNextBatch(limit: Int) {
        isLoadingMore = true
        viewModelScope.launch {
            repository.getConversations(limit, offset)
                .catch { e ->
                    if (allConversations.isEmpty()) {
                        _uiState.value = ConversationListUiState.Error(e.message ?: "Unknown error")
                    }
                    isLoadingMore = false
                }
                .collect { newList ->
                    if (newList.isEmpty()) {
                        hasMore = false
                        if (allConversations.isEmpty()) {
                            _uiState.value = ConversationListUiState.Success(emptyList())
                        }
                    } else {
                        val visibleList = newList.filterNot { it.threadId in pendingDeletedThreadIds }
                        allConversations.addAll(visibleList)
                        offset += newList.size
                        _uiState.value = ConversationListUiState.Success(allConversations.toList())
                    }
                    isLoadingMore = false
                }
        }
    }

    fun refreshSilent() {
        if (isLoadingMore || allConversations.isEmpty()) return
        syncLoadedConversations()
    }

    private fun syncLoadedConversations() {
        viewModelScope.launch {
            repository.getConversations(maxOf(100, offset), 0)
                .catch { /* ignore error during silent refresh */ }
                .collect { newList ->
                    replaceConversations(newList)
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
            syncLoadedConversations()
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
        syncLoadedConversations()
    }

    private fun replaceConversations(conversations: List<ConversationModel>) {
        val visibleList = conversations.filterNot { it.threadId in pendingDeletedThreadIds }
        allConversations.clear()
        allConversations.addAll(visibleList)
        offset = conversations.size
        hasMore = conversations.isNotEmpty()
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }
}

sealed class ConversationListUiState {
    data object Loading : ConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) : ConversationListUiState()
    data class Error(val message: String) : ConversationListUiState()
}
