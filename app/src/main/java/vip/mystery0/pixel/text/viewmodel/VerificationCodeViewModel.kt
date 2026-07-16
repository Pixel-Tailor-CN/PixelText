package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
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
import vip.mystery0.pixel.text.worker.VerificationCodeIndexScheduler
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

private data class VerificationCodeTransientState(
    val isRefreshing: Boolean,
    val isRebuilding: Boolean,
    val loadingBodies: Set<Long>,
    val expandedMessageIds: Set<Long>,
    val messageBodies: Map<Long, String>,
    val errorMessage: String?,
)

private data class VerificationCodeBodyState(
    val expandedMessageIds: Set<Long>,
    val messageBodies: Map<Long, String>,
    val errorMessage: String?,
)

sealed interface VerificationCodeEvent {
    data class ShowMessage(val message: String) : VerificationCodeEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class VerificationCodeViewModel(
    private val repository: VerificationCodeRepository,
    private val savedStateHandle: SavedStateHandle,
    private val scheduler: VerificationCodeIndexScheduler,
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

    private val operationState = combine(
        refreshing,
        rebuilding,
        loadingBodies,
    ) { isRefreshing, isRebuilding, loading -> Triple(isRefreshing, isRebuilding, loading) }

    private val bodyState = combine(expandedIds, bodies, error) { expanded, cachedBodies, message ->
        VerificationCodeBodyState(expanded, cachedBodies, message)
    }

    private val transientState = combine(operationState, bodyState) { operation, body ->
        VerificationCodeTransientState(
            isRefreshing = operation.first,
            isRebuilding = operation.second,
            loadingBodies = operation.third,
            expandedMessageIds = body.expandedMessageIds,
            messageBodies = body.messageBodies,
            errorMessage = body.errorMessage,
        )
    }

    val uiState: StateFlow<VerificationCodeUiState> = combine(
        pages,
        months,
        transientState,
    ) { currentPages, allMonths, transient ->
        VerificationCodeUiState(
            pages = currentPages,
            isInitializing = currentPages.isEmpty() &&
                (transient.isRefreshing || transient.isRebuilding),
            isRefreshing = transient.isRefreshing,
            isRebuilding = transient.isRebuilding,
            loadingBodies = transient.loadingBodies,
            expandedMessageIds = transient.expandedMessageIds,
            messageBodies = transient.messageBodies,
            canLoadMore = currentPages.size < allMonths.size,
            errorMessage = transient.errorMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VerificationCodeUiState())

    private var refreshJob: Job? = null
    private val bodyCache = object : LinkedHashMap<Long, String>(BODY_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
            size > BODY_CACHE_SIZE
    }

    init {
        viewModelScope.launch {
            scheduler.observeIsRunning().collect { running ->
                if (!running) {
                    refreshing.value = false
                    rebuilding.value = false
                }
            }
        }
        viewModelScope.launch {
            scheduler.observeLatestState().collect { state ->
                error.value = if (state == WorkInfo.State.FAILED) {
                    "无法访问短信数据，请检查短信权限和默认短信应用设置"
                } else {
                    null
                }
            }
        }
        refresh()
        viewModelScope.launch {
            pages.collect { loadedPages ->
                val loadedIds = loadedPages.flatMap { it.messages }
                    .mapTo(mutableSetOf()) { it.messageId }
                expandedIds.value.intersect(loadedIds).forEach { messageId ->
                    if (messageId !in bodies.value && messageId !in loadingBodies.value) {
                        loadMessageBody(messageId)
                    }
                }
            }
        }
    }

    fun loadNextMonth() {
        if (loadedMonthCount.value < months.value.size) loadedMonthCount.value += 1
    }

    fun refresh() {
        refreshing.value = true
        scheduler.scheduleReconcile()
    }

    fun rebuildAll() {
        rebuilding.value = true
        scheduler.scheduleFullRebuild()
        eventsMutable.tryEmit(VerificationCodeEvent.ShowMessage("已安排重新识别全部短信"))
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
        loadMessageBody(messageId)
    }

    private fun loadMessageBody(messageId: Long) {
        if (messageId in loadingBodies.value) return
        loadingBodies.value += messageId
        viewModelScope.launch {
            val result = runCatching { repository.getMessageBody(messageId) }
            loadingBodies.value -= messageId
            result.onFailure {
                updateExpanded(expandedIds.value - messageId)
                eventsMutable.emit(VerificationCodeEvent.ShowMessage("读取原文失败，请重试"))
            }.onSuccess { body ->
                if (body != null) {
                    bodyCache[messageId] = body
                    bodies.value = bodyCache.toMap()
                    return@onSuccess
                }
                updateExpanded(expandedIds.value - messageId)
                runCatching { repository.deleteMessageIds(listOf(messageId)) }
                    .onSuccess {
                        eventsMutable.emit(VerificationCodeEvent.ShowMessage("原短信已不存在"))
                    }
                    .onFailure {
                        eventsMutable.emit(
                            VerificationCodeEvent.ShowMessage("原短信已不存在，但清理索引失败，请重试")
                        )
                    }
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
