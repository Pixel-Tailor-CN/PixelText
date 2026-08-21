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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.VerificationCodeIndexModel
import vip.mystery0.pixel.text.domain.model.VerificationCodeMonthModel
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.settings.MessageTimeDisplayFormat
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
    val showOriginalByDefault: Boolean = true,
    val messageTimeDisplayFormat: MessageTimeDisplayFormat = MessageTimeDisplayFormat.HUMANIZED,
    val toggledMessageIds: Set<Long> = emptySet(),
    val messageBodies: Map<Long, String> = emptyMap(),
    val canLoadMore: Boolean = false,
    val errorMessage: String? = null,
)

private data class VerificationCodeTransientState(
    val isRefreshing: Boolean,
    val isRebuilding: Boolean,
    val loadingBodies: Set<Long>,
    val showOriginalByDefault: Boolean,
    val messageTimeDisplayFormat: MessageTimeDisplayFormat,
    val toggledMessageIds: Set<Long>,
    val messageBodies: Map<Long, String>,
    val errorMessage: String?,
)

private data class VerificationCodeBodyState(
    val toggledMessageIds: Set<Long>,
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
    private val settingsRepository: AppSettingsRepository,
) : ViewModel() {
    private val loadedMonthCount = MutableStateFlow(1)
    private val refreshing = MutableStateFlow(false)
    private val rebuilding = MutableStateFlow(false)
    private val loadingBodies = MutableStateFlow<Set<Long>>(emptySet())
    private val toggledIds = MutableStateFlow(
        savedStateHandle.get<LongArray>(TOGGLED_IDS_KEY)?.toSet().orEmpty()
    )
    private val bodies = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val error = MutableStateFlow<String?>(null)
    private val eventsMutable = MutableSharedFlow<VerificationCodeEvent>(extraBufferCapacity = 1)
    val events = eventsMutable.asSharedFlow()

    private val showOriginalByDefault = settingsRepository.settings
        .map { it.showVerificationCodeContentByDefault }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            settingsRepository.settings.value.showVerificationCodeContentByDefault,
        )

    private val messageTimeDisplayFormat = settingsRepository.settings
        .map { it.messageTimeDisplayFormat }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            settingsRepository.settings.value.messageTimeDisplayFormat,
        )

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

    private val bodyState = combine(toggledIds, bodies, error) { toggled, cachedBodies, message ->
        VerificationCodeBodyState(toggled, cachedBodies, message)
    }

    private val transientState = combine(
        operationState,
        bodyState,
        showOriginalByDefault,
        messageTimeDisplayFormat,
    ) { operation, body, showOriginal, timeDisplayFormat ->
        VerificationCodeTransientState(
            isRefreshing = operation.first,
            isRebuilding = operation.second,
            loadingBodies = operation.third,
            showOriginalByDefault = showOriginal,
            messageTimeDisplayFormat = timeDisplayFormat,
            toggledMessageIds = body.toggledMessageIds,
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
            showOriginalByDefault = transient.showOriginalByDefault,
            messageTimeDisplayFormat = transient.messageTimeDisplayFormat,
            toggledMessageIds = transient.toggledMessageIds,
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
            var previousDefault = showOriginalByDefault.value
            showOriginalByDefault.collect { currentDefault ->
                if (currentDefault != previousDefault) {
                    updateToggled(emptySet())
                    previousDefault = currentDefault
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
        val currentlyVisible = isOriginalVisible(messageId)
        setOriginalVisible(messageId, !currentlyVisible)
        if (!currentlyVisible) ensureMessageBodyLoaded(messageId)
    }

    fun ensureMessageBodyLoaded(messageId: Long) {
        if (!isOriginalVisible(messageId)) return
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
                setOriginalVisible(messageId, false)
                eventsMutable.emit(VerificationCodeEvent.ShowMessage("读取原文失败，请重试"))
            }.onSuccess { body ->
                if (body != null) {
                    bodyCache[messageId] = body
                    bodies.value = bodyCache.toMap()
                    return@onSuccess
                }
                setOriginalVisible(messageId, false)
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

    private fun isOriginalVisible(messageId: Long): Boolean =
        showOriginalByDefault.value.xor(messageId in toggledIds.value)

    private fun setOriginalVisible(messageId: Long, visible: Boolean) {
        val shouldToggle = visible != showOriginalByDefault.value
        updateToggled(
            if (shouldToggle) toggledIds.value + messageId
            else toggledIds.value - messageId
        )
    }

    private fun updateToggled(ids: Set<Long>) {
        toggledIds.value = ids
        savedStateHandle[TOGGLED_IDS_KEY] = ids.toLongArray()
    }

    private companion object {
        const val BODY_CACHE_SIZE = 30
        const val TOGGLED_IDS_KEY = "verification_code_toggled_ids"
    }
}
