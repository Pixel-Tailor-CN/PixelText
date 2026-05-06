package vip.mystery0.pixel.text.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.repository.MessageRepository

class ConversationDetailViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
    val uiState: StateFlow<MessageUiState> = _uiState.asStateFlow()

    private val _address = MutableStateFlow<String>("")
    val address: StateFlow<String> = _address.asStateFlow()

    fun loadThread(threadId: Long, address: String) {
        _address.value = address
        viewModelScope.launch {
            _uiState.value = MessageUiState.Loading
            repository.getMessagesByThread(threadId)
                .catch { e ->
                    _uiState.value = MessageUiState.Error(e.message ?: "Unknown error")
                }
                .collect { messages ->
                    _uiState.value = MessageUiState.Success(messages)
                }
        }
    }
}
