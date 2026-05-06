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

    fun loadConversations(force: Boolean = false) {
        if (!force && _uiState.value is ConversationListUiState.Success) return
        
        viewModelScope.launch {
            _uiState.value = ConversationListUiState.Loading
            repository.getConversations()
                .catch { e ->
                    _uiState.value = ConversationListUiState.Error(e.message ?: "Unknown error")
                }
                .collect { list ->
                    _uiState.value = ConversationListUiState.Success(list)
                }
        }
    }
}

sealed class ConversationListUiState {
    data object Loading : ConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) : ConversationListUiState()
    data class Error(val message: String) : ConversationListUiState()
}
