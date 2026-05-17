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

class SpamConversationListViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _uiState =
        MutableStateFlow<SpamConversationListUiState>(SpamConversationListUiState.Loading)
    val uiState: StateFlow<SpamConversationListUiState> = _uiState.asStateFlow()

    private val conversations = mutableListOf<ConversationModel>()
    private var offset = 0
    private var isLoadingMore = false
    private var hasMore = true

    fun loadSpamConversations(force: Boolean = false) {
        if (force) {
            offset = 0
            conversations.clear()
            hasMore = true
            _uiState.value = SpamConversationListUiState.Loading
        } else if (conversations.isNotEmpty()) {
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
            repository.getSpamConversations(limit, offset)
                .catch { e ->
                    if (conversations.isEmpty()) {
                        _uiState.value =
                            SpamConversationListUiState.Error(e.message ?: "Unknown error")
                    }
                    isLoadingMore = false
                }
                .collect { newList ->
                    if (newList.isEmpty()) {
                        hasMore = false
                        if (conversations.isEmpty()) {
                            _uiState.value = SpamConversationListUiState.Success(emptyList())
                        }
                    } else {
                        mergeConversations(newList)
                        offset += newList.size
                        _uiState.value =
                            SpamConversationListUiState.Success(conversations.toList())
                    }
                    isLoadingMore = false
                }
        }
    }

    private fun mergeConversations(newList: List<ConversationModel>) {
        val currentByThreadId = conversations.associateBy { it.threadId }
        val merged = (conversations + newList)
            .distinctBy { it.threadId }
            .map { conversation ->
                currentByThreadId[conversation.threadId]?.let { existing ->
                    if (conversation.timestamp >= existing.timestamp) conversation else existing
                } ?: conversation
            }
            .sortedByDescending { it.timestamp }

        conversations.clear()
        conversations.addAll(merged)
    }
}

sealed class SpamConversationListUiState {
    data object Loading : SpamConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) : SpamConversationListUiState()
    data class Error(val message: String) : SpamConversationListUiState()
}
