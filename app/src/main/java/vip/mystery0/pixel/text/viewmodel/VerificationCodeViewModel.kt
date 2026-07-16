package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.VerificationCodeIndexModel
import vip.mystery0.pixel.text.domain.model.VerificationCodeMonthModel
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import java.util.LinkedHashMap

data class VerificationCodeMonthPage(
    val month: VerificationCodeMonthModel,
    val messages: List<VerificationCodeIndexModel>,
)

data class VerificationCodeUiState(
    val pages: List<VerificationCodeMonthPage> = emptyList(),
    val isInitializing: Boolean = true,
    val isRefreshing: Boolean = false,
    val isRebuilding: Boolean = false,
    val loadingBodies: Set<Long> = emptySet(),
    val expandedMessageIds: Set<Long> = emptySet(),
    val messageBodies: Map<Long, String> = emptyMap(),
    val canLoadMore: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface VerificationCodeEvent {
    data class ShowMessage(val message: String) : VerificationCodeEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class VerificationCodeViewModel(
    private val repository: VerificationCodeRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val loadedMonthCount = MutableStateFlow(1)
    private val refreshing = MutableStateFlow(false)
    private val rebuilding = MutableStateFlow(false)
    private val loadingBodies = MutableStateFlow<Set<Long>>(emptySet())
    private val expandedIds = MutableStateFlow(
        savedStateHandle.get<LongArray>(EXPANDED_IDS_KEY)?.toSet().orEmpty()
    )
    private val bodies = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val error = MutableStateFlow<String?>(null)
    private val eventsMutable = MutableSharedFlow<VerificationCodeEvent>(extraBufferCapacity = 1)
    val events = eventsMutable.asSharedFlow()

    private val months = repository.observeMonths()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val pages: StateFlow<List<VerificationCodeMonthPage>> =
        combine(months, loadedMonthCount) { allMonths, count -> allMonths.take(count) }
            .flatMapLatest { visibleMonths ->
                if (visibleMonths.isEmpty()) {
                    MutableStateFlow(emptyList())
                } else {
                    combine(visibleMonths.map { month ->
                        repository.observeMonth(month.monthKey)
                    }) { messageLists ->
                        visibleMonths.mapIndexed { index, month ->
                            VerificationCodeMonthPage(month, messageLists[index])
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<VerificationCodeUiState> = combine(
        pages,
        months,
        refreshing,
        rebuilding,
        loadingBodies,
        expandedIds,
        bodies,
        error,
    ) { values ->
        val currentPages = values[0] as List<VerificationCodeMonthPage>
        val allMonths = values[1] as List<VerificationCodeMonthModel>
        VerificationCodeUiState(
            pages = currentPages,
            isInitializing = currentPages.isEmpty() && (values[2] as Boolean || values[3] as Boolean),
            isRefreshing = values[2] as Boolean,
            isRebuilding = values[3] as Boolean,
            loadingBodies = values[4] as Set<Long>,
            expandedMessageIds = values[5] as Set<Long>,
            messageBodies = values[6] as Map<Long, String>,
            canLoadMore = currentPages.size < allMonths.size,
            errorMessage = values[7] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VerificationCodeUiState())

    private var refreshJob: Job? = null
    private val bodyCache = object : LinkedHashMap<Long, String>(BODY_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
            size > BODY_CACHE_SIZE
    }

    init {
        refresh()
    }

    fun loadNextMonth() {
        if (loadedMonthCount.value < months.value.size) loadedMonthCount.value += 1
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshing.value = true
            error.value = null
            runCatching { repository.reconcile() }
                .onFailure {
                    error.value = "刷新验证码失败"
                    eventsMutable.emit(VerificationCodeEvent.ShowMessage("刷新验证码失败"))
                }
            refreshing.value = false
        }
    }

    fun rebuildAll() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            rebuilding.value = true
            error.value = null
            runCatching { repository.rebuildAll() }
                .onSuccess { eventsMutable.emit(VerificationCodeEvent.ShowMessage("已重新识别全部短信")) }
                .onFailure {
                    error.value = "重新识别失败"
                    eventsMutable.emit(VerificationCodeEvent.ShowMessage("重新识别失败"))
                }
            rebuilding.value = false
        }
    }

    fun toggleMessageMode(messageId: Long) {
        if (messageId in expandedIds.value) {
            updateExpanded(expandedIds.value - messageId)
            return
        }
        updateExpanded(expandedIds.value + messageId)
        bodyCache[messageId]?.let {
            bodies.value = bodyCache.toMap()
            return
        }
        if (messageId in loadingBodies.value) return
        viewModelScope.launch {
            loadingBodies.value += messageId
            val body = runCatching { repository.getMessageBody(messageId) }.getOrNull()
            loadingBodies.value -= messageId
            if (body == null) {
                repository.deleteMessageIds(listOf(messageId))
                updateExpanded(expandedIds.value - messageId)
                eventsMutable.emit(VerificationCodeEvent.ShowMessage("原短信已不存在"))
            } else {
                bodyCache[messageId] = body
                bodies.value = bodyCache.toMap()
            }
        }
    }

    private fun updateExpanded(ids: Set<Long>) {
        expandedIds.value = ids
        savedStateHandle[EXPANDED_IDS_KEY] = ids.toLongArray()
    }

    private companion object {
        const val BODY_CACHE_SIZE = 30
        const val EXPANDED_IDS_KEY = "verification_code_expanded_ids"
    }
}
