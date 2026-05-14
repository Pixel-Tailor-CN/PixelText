package vip.mystery0.pixel.text.ui.message

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
                    } else {
                        allConversations.addAll(newList)
                        offset += newList.size
                        _uiState.value = ConversationListUiState.Success(allConversations.toList())
                    }
                    isLoadingMore = false
                }
        }
    }

    fun refreshSilent() {
        if (isLoadingMore || allConversations.isEmpty()) return
        viewModelScope.launch {
            repository.getConversations(maxOf(100, offset), 0)
                .catch { /* ignore error during silent refresh */ }
                .collect { newList ->
                    allConversations.clear()
                    allConversations.addAll(newList)
                    _uiState.value = ConversationListUiState.Success(allConversations.toList())
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
}

sealed class ConversationListUiState {
    data object Loading : ConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) : ConversationListUiState()
    data class Error(val message: String) : ConversationListUiState()
}
