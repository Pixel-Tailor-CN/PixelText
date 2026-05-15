package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository

class MessageViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
    val uiState: StateFlow<MessageUiState> = _uiState.asStateFlow()

    fun loadMessages() {
        viewModelScope.launch {
            _uiState.value = MessageUiState.Loading
            repository.getMessages()
                .catch { e ->
                    _uiState.value = MessageUiState.Error(e.message ?: "Unknown error")
                }
                .collect { messages ->
                    _uiState.value = MessageUiState.Success(messages)
                }
        }
    }
}

sealed class MessageUiState {
    data object Loading : MessageUiState()
    data class Success(val messages: List<MessageModel>) : MessageUiState()
    data class Error(val message: String) : MessageUiState()
}
