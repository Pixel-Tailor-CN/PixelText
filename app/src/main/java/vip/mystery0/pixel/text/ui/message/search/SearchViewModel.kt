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

    private val _selectionState = MutableStateFlow(SearchSelectionState())
    val selectionState: StateFlow<SearchSelectionState> = _selectionState.asStateFlow()

    fun toggleSelection(messageId: Long) {
        val state = _selectionState.value
        if (state.isDeleting || state.pendingDelete != null) return
        val results = (_uiState.value as? SearchUiState.Success)?.results ?: return
        if (results.none { it.id == messageId }) return
        val selected = state.selectedIds
        _selectionState.value = state.copy(
            selectedIds = if (messageId in selected) selected - messageId else selected + messageId,
        )
    }

    fun toggleSelectAll() {
        val state = _selectionState.value
        if (state.isDeleting || state.pendingDelete != null) return
        val ids = (_uiState.value as? SearchUiState.Success)?.results
            ?.mapTo(mutableSetOf()) { it.id } ?: return
        // 只捕获当前结果，不将后续新增的匹配消息自动加入选择。
        _selectionState.value = state.copy(
            selectedIds = if (state.selectedIds.containsAll(ids)) emptySet() else ids,
        )
    }

    fun clearSelection() {
        if (_selectionState.value.isDeleting) return
        _selectionState.value = _selectionState.value.copy(selectedIds = emptySet(), pendingDelete = null)
    }

    fun requestDelete() {
        val state = _selectionState.value
        val success = _uiState.value as? SearchUiState.Success ?: return
        if (state.isDeleting || state.selectedIds.isEmpty() || state.pendingDelete != null) return
        _selectionState.value = state.copy(
            pendingDelete = SearchDeleteSnapshot(state.selectedIds.toSet(), success.incomplete),
        )
    }

    fun dismissDelete() {
        if (_selectionState.value.isDeleting) return
        _selectionState.value = _selectionState.value.copy(pendingDelete = null)
    }

    fun consumeFeedback() {
        _selectionState.value = _selectionState.value.copy(feedback = null)
    }

    fun confirmDelete() {
        val state = _selectionState.value
        val snapshot = state.pendingDelete ?: return
        if (state.isDeleting) return
        // 确认后仅使用对话框打开时捕获的 ID，避免实时搜索更新扩大删除范围。
        _selectionState.value = state.copy(
            isDeleting = true, pendingDelete = null, feedback = null,
        )
        viewModelScope.launch {
            try {
                val deletedCount = repository.deleteMessages(snapshot.messageIds)
                val feedback = if (deletedCount == snapshot.messageIds.size) {
                    "已删除 $deletedCount 条消息"
                } else {
                    "已删除 $deletedCount 条消息，其余消息未删除或已不存在，请检查搜索结果"
                }
                _selectionState.value = _selectionState.value.copy(feedback = feedback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _selectionState.value = _selectionState.value.copy(
                    feedback = "删除未完成，部分消息可能已删除，请检查搜索结果后重试",
                )
            } finally {
                _selectionState.value = _selectionState.value.copy(
                    isDeleting = false, selectedIds = emptySet(),
                )
            }
            triggerSearch(debounce = false)
        }
    }

    private var searchJob: Job? = null
    private var requestGeneration = 0L

    fun updateQuery(query: String) {
        if (_selectionState.value.isDeleting) return
        _searchQuery.value = query
        triggerSearch(debounce = true)
    }

    fun setDomainFilter(filter: MessageSearchFilter) {
        if (_selectionState.value.isDeleting) return
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
        if (_selectionState.value.isDeleting) return
        clearSelection()
        // 同步取消前代 Job 并递增代次，取消边界严格在 delay 之前，且使清空回 Idle 也让旧代次失效
        searchJob?.cancel()
        val generation = ++requestGeneration
        val query = _searchQuery.value
        val filter = _domainFilter.value

        if (query.isBlank() && !filter.isActive()) {
            _uiState.value = SearchUiState.Idle
            return
        }

        // 防抖期间也不允许继续选择上一轮结果。
        _uiState.value = SearchUiState.Loading
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
                    val selection = _selectionState.value
                    val resultIds = batch.messages.mapTo(mutableSetOf()) { it.id }
                    _selectionState.value = selection.copy(
                        selectedIds = selection.selectedIds.intersect(resultIds),
                    )
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

data class SearchDeleteSnapshot(val messageIds: Set<Long>, val incomplete: Boolean)

data class SearchSelectionState(
    val selectedIds: Set<Long> = emptySet(),
    val pendingDelete: SearchDeleteSnapshot? = null,
    val isDeleting: Boolean = false,
    val feedback: String? = null,
)

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
