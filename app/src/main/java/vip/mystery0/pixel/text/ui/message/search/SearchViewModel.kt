package vip.mystery0.pixel.text.ui.message.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository

@OptIn(FlowPreview::class)
class SearchViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        _searchQuery
            .debounce(300)
            .distinctUntilChanged()
            .onEach { query ->
                if (query.isBlank()) {
                    _uiState.value = SearchUiState.Idle
                } else {
                    performSearch(query)
                }
            }
            .launchIn(viewModelScope)
    }

    fun updateQuery(query: String) {
        _searchQuery.value = query
    }

    private suspend fun performSearch(query: String) {
        _uiState.value = SearchUiState.Loading
        repository.searchMessages(query)
            .catch { e ->
                _uiState.value = SearchUiState.Error(e.message ?: "Search failed")
            }
            .collect { results ->
                _uiState.value = SearchUiState.Success(results)
            }
    }
}

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Loading : SearchUiState()
    data class Success(val results: List<MessageModel>) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}
