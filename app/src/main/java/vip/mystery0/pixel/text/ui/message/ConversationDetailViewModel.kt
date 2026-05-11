package vip.mystery0.pixel.text.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository

class ConversationDetailViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
    val uiState: StateFlow<MessageUiState> = _uiState.asStateFlow()

    private val _address = MutableStateFlow<String>("")
    val address: StateFlow<String> = _address.asStateFlow()

    private var currentThreadId: Long = -1L
    private var offset = 0
    private var isLoadingMore = false
    private var hasMore = true
    private val _messages = mutableListOf<MessageModel>()

    fun loadThread(threadId: Long, address: String) {
        _address.value = address
        if (currentThreadId == threadId && _messages.isNotEmpty()) return

        currentThreadId = threadId
        _messages.clear()
        offset = 0
        hasMore = true
        _uiState.value = MessageUiState.Loading
        fetchMessages()

        viewModelScope.launch {
            repository.markThreadAsRead(threadId)
        }
    }

    fun loadMore() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        fetchMessages()
    }

    private fun fetchMessages() {
        viewModelScope.launch {
            repository.getMessagesByThread(currentThreadId, 20, offset)
                .catch { e ->
                    if (_messages.isEmpty()) {
                        _uiState.value = MessageUiState.Error(e.message ?: "Unknown error")
                    }
                    isLoadingMore = false
                }
                .collect { newMessages ->
                    if (newMessages.isEmpty()) {
                        hasMore = false
                    } else {
                        _messages.addAll(newMessages)
                        offset += newMessages.size
                        _uiState.value = MessageUiState.Success(_messages.toList())
                    }
                    isLoadingMore = false
                }
        }
    }
}
