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

class ArchivedConversationListViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _uiState =
        MutableStateFlow<ArchivedConversationListUiState>(ArchivedConversationListUiState.Loading)
    val uiState: StateFlow<ArchivedConversationListUiState> = _uiState.asStateFlow()

    private val archivedConversations = mutableListOf<ConversationModel>()
    private var offset = 0
    private var isLoadingMore = false
    private var hasMore = true

    fun loadArchivedConversations(force: Boolean = false) {
        if (force) {
            offset = 0
            archivedConversations.clear()
            hasMore = true
            _uiState.value = ArchivedConversationListUiState.Loading
        } else if (archivedConversations.isNotEmpty()) {
            return
        }

        fetchNextBatch(100)
    }

    fun loadMore() {
        if (isLoadingMore || !hasMore) return
        fetchNextBatch(50)
    }

    fun unarchiveSelected(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        viewModelScope.launch {
            repository.unarchiveThreads(threadIds)
            loadArchivedConversations(force = true)
        }
    }

    private fun fetchNextBatch(limit: Int) {
        isLoadingMore = true
        viewModelScope.launch {
            repository.getArchivedConversations(limit, offset)
                .catch { e ->
                    if (archivedConversations.isEmpty()) {
                        _uiState.value =
                            ArchivedConversationListUiState.Error(e.message ?: "Unknown error")
                    }
                    isLoadingMore = false
                }
                .collect { newList ->
                    if (newList.isEmpty()) {
                        hasMore = false
                        if (archivedConversations.isEmpty()) {
                            _uiState.value =
                                ArchivedConversationListUiState.Success(emptyList())
                        }
                    } else {
                        archivedConversations.addAll(newList)
                        offset += newList.size
                        _uiState.value =
                            ArchivedConversationListUiState.Success(archivedConversations.toList())
                    }
                    isLoadingMore = false
                }
        }
    }
}

sealed class ArchivedConversationListUiState {
    data object Loading : ArchivedConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) :
        ArchivedConversationListUiState()

    data class Error(val message: String) : ArchivedConversationListUiState()
}
