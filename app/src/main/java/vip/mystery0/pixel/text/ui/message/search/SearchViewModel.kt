package vip.mystery0.pixel.text.ui.message.search

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.source.PickedPhone
import vip.mystery0.pixel.text.data.source.PickedPhoneSource
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.search.MessageSearchFilter
import vip.mystery0.pixel.text.domain.model.search.MessageSearchRequest
import vip.mystery0.pixel.text.domain.model.search.SearchDate
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds

class SearchViewModel(
    private val repository: MessageRepository,
    private val pickedPhoneSource: PickedPhoneSource,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _domainFilter = MutableStateFlow(MessageSearchFilter())
    val domainFilter: StateFlow<MessageSearchFilter> = _domainFilter.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var requestGeneration = 0L

    fun updateQuery(query: String) {
        _searchQuery.value = query
        triggerSearch(debounce = true)
    }

    fun setDomainFilter(filter: MessageSearchFilter) {
        _domainFilter.value = filter
        triggerSearch(debounce = false)
    }

    fun refreshSims(activeSubIds: Set<Int>) {
        val current = _domainFilter.value
        val normalized = current.normalizeSims(activeSubIds)
        if (normalized != current) {
            setDomainFilter(normalized)
        }
    }

    fun retry() {
        triggerSearch(debounce = false)
    }

    private fun triggerSearch(debounce: Boolean) {
        // 同步取消前代 Job 并递增代次，取消边界严格在 delay 之前，且使清空回 Idle 也让旧代次失效
        searchJob?.cancel()
        val generation = ++requestGeneration
        val query = _searchQuery.value
        val filter = _domainFilter.value

        if (query.isBlank() && !filter.isActive()) {
            _uiState.value = SearchUiState.Idle
            return
        }

        searchJob = viewModelScope.launch {
            if (debounce) {
                delay(300.milliseconds)
            }
            executeSearch(query, filter, generation)
        }
    }

    private suspend fun executeSearch(query: String, filter: MessageSearchFilter, generation: Long) {
        if (generation != requestGeneration) return
        _uiState.value = SearchUiState.Loading

        // 每次实际查询按设备默认时区捕获一次 ZonedDateTime/截止毫秒，Room 失效重发时不重算
        val cutoff = filter.date.cutoff(ZonedDateTime.now())
        val request = MessageSearchRequest(
            query = query,
            filter = filter,
            beforeTimestampExclusive = cutoff,
        )

        try {
            repository.searchMessages(request)
                .collect { batch ->
                    if (generation != requestGeneration) return@collect
                    _uiState.value = SearchUiState.Success(
                        results = batch.messages,
                        actualQuery = query,
                        incomplete = batch.incomplete,
                        generation = generation,
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (generation == requestGeneration) {
                _uiState.value = SearchUiState.Error("搜索失败，请稍后重试")
            }
        }
    }

    fun setPhoneNumberFilter(number: String?, displayName: String?) {
        val current = _domainFilter.value
        val nextNumber = number?.trim()?.takeIf { it.isNotBlank() }
        val nextName = displayName?.trim()?.takeIf { it.isNotBlank() }
        setDomainFilter(current.copy(phoneNumber = nextNumber, phoneDisplayName = nextName))
    }

    fun clearPhoneNumberFilter() {
        setPhoneNumberFilter(null, null)
    }

    fun setSimFilter(subIds: Set<Int>, activeSubIds: Set<Int> = emptySet()) {
        val current = _domainFilter.value
        val normalized = current.copy(simSubIds = subIds).normalizeSims(activeSubIds)
        setDomainFilter(normalized)
    }

    fun setTransportFilter(transports: Set<MessageTransport>) {
        val current = _domainFilter.value
        setDomainFilter(current.copy(transports = transports))
    }

    fun setDateFilter(date: SearchDate) {
        val current = _domainFilter.value
        setDomainFilter(current.copy(date = date))
    }

    fun toggleUnreadFilter() {
        val current = _domainFilter.value
        setDomainFilter(current.copy(unreadOnly = !current.unreadOnly))
    }

    suspend fun resolvePickedPhone(uri: Uri): PickedPhone? {
        return pickedPhoneSource.resolvePickedPhone(uri)
    }
}

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Loading : SearchUiState()
    data class Success(
        val results: List<MessageModel>,
        val actualQuery: String = "",
        val incomplete: Boolean = false,
        val generation: Long = 0L,
    ) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}
