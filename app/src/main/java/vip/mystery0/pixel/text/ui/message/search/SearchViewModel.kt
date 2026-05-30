package vip.mystery0.pixel.text.ui.message.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.repository.MessageSearchFilter
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class SearchViewModel(private val repository: MessageRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchFilter = MutableStateFlow(MessageSearchFilter())
    val searchFilter: StateFlow<MessageSearchFilter> = _searchFilter.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        combine(
            _searchQuery.debounce(300.milliseconds),
            _searchFilter
        ) { query, filter -> query to filter }
            .distinctUntilChanged()
            .onEach { (query, filter) ->
                if (query.isBlank() && !filter.isActive()) {
                    _uiState.value = SearchUiState.Idle
                } else {
                    performSearch(query, filter)
                }
            }
            .launchIn(viewModelScope)
    }

    fun updateQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleUnreadFilter() {
        _searchFilter.value = _searchFilter.value.let {
            it.copy(unreadOnly = !it.unreadOnly)
        }
    }

    fun toggleSimFilter(subId: Int) {
        _searchFilter.value = _searchFilter.value.let {
            it.copy(simSubId = if (it.simSubId == subId) null else subId)
        }
    }

    fun toggleMmsFilter() {
        _searchFilter.value = _searchFilter.value.let {
            it.copy(mmsOnly = !it.mmsOnly)
        }
    }

    private suspend fun performSearch(query: String, filter: MessageSearchFilter) {
        _uiState.value = SearchUiState.Loading
        repository.searchMessages(query, filter)
            .catch { e ->
                _uiState.value = SearchUiState.Error(e.message ?: "Search failed")
            }
            .collect { results ->
                _uiState.value = SearchUiState.Success(results)
            }
    }

    private fun MessageSearchFilter.isActive(): Boolean {
        return unreadOnly || simSubId != null || mmsOnly
    }
}

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Loading : SearchUiState()
    data class Success(val results: List<MessageModel>) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}
