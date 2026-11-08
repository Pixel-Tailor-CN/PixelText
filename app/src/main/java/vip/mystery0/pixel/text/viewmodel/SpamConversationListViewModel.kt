package vip.mystery0.pixel.text.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.repository.MirrorSpamScanner
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.worker.HistoricalSpamScanWorker
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class SpamConversationListViewModel(
    private val repository: MessageRepository,
    private val scanner: MirrorSpamScanner,
    private val mirror: MessageMirrorRepository,
    context: Context
) : ViewModel() {
    private val _uiState =
        MutableStateFlow<SpamConversationListUiState>(SpamConversationListUiState.Loading)
    val uiState: StateFlow<SpamConversationListUiState> = _uiState.asStateFlow()

    private val _historyStatsState =
        MutableStateFlow<HistorySpamStatsUiState>(HistorySpamStatsUiState.Idle)
    val historyStatsState: StateFlow<HistorySpamStatsUiState> = _historyStatsState.asStateFlow()

    private val _historyScanProgress = MutableStateFlow<HistorySpamScanProgress?>(null)
    val historyScanProgress: StateFlow<HistorySpamScanProgress?> =
        _historyScanProgress.asStateFlow()

    private val _isScanProgressHidden = MutableStateFlow(false)
    val isScanProgressHidden: StateFlow<Boolean> =
        _isScanProgressHidden.asStateFlow()

    private val conversations = mutableListOf<ConversationModel>()
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private var offset = 0
    private var isLoadingMore = false
    private var listJob: Job? = null
    private var silentRefreshPending = false
    private var observationJob: Job? = null
    private var hasMore = true
    private var listRequestVersion = 0
    private var observedScanId: UUID? = null

    init {
        observeHistoricalScans()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startObserving() {
        if (observationJob?.isActive == true) return
        observationJob = viewModelScope.launch {
            // 消息和最终判定现在位于同库，统一观察镜像即可覆盖两种更新。
            mirror.observeConversationChanges().debounce(100.milliseconds).collect {
                loadSpamConversations(force = true, showLoading = false)
            }
        }
    }

    fun stopObserving() {
        observationJob?.cancel()
        observationJob = null
    }

    fun loadSpamConversations(
        force: Boolean = false,
        showLoading: Boolean = true,
    ) {
        // 持续同步时不能反复取消较慢的查询；合并为完成后再刷新一次，既不饿死查询也不丢事件。
        if (force && !showLoading && isLoadingMore) {
            silentRefreshPending = true
            return
        }
        val keepCurrentContent = force &&
            !showLoading &&
            _uiState.value is SpamConversationListUiState.Success
        val visibleConversations = (_uiState.value as? SpamConversationListUiState.Success)?.conversations
        val fallbackSnapshot = if (keepCurrentContent) {
            SpamConversationListSnapshot(visibleConversations.orEmpty(), visibleConversations.orEmpty().size, hasMore)
        } else {
            null
        }
        val refreshLimit = maxOf(100, visibleConversations?.size ?: 0)
        if (force) {
            listRequestVersion += 1
            silentRefreshPending = false
            listJob?.cancel()
            offset = 0
            conversations.clear()
            hasMore = true
            if (!keepCurrentContent) {
                _uiState.value = SpamConversationListUiState.Loading
            }
        } else if (isLoadingMore || conversations.isNotEmpty()) {
            return
        }

        fetchNextBatch(
            limit = refreshLimit,
            showError = !keepCurrentContent,
            fallbackSnapshot = fallbackSnapshot,
        )
    }

    fun loadMore() {
        if (isLoadingMore || !hasMore) return
        fetchNextBatch(50)
    }

    fun loadHistoryStats() {
        _historyStatsState.value = HistorySpamStatsUiState.Loading
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stats = scanner.statistics()
                    HistorySpamStats(totalCount = stats.total, identifiedCount = stats.total - stats.pending)
                }
            }
            _historyStatsState.value = result.fold(
                onSuccess = { HistorySpamStatsUiState.Success(it) },
                onFailure = {
                    HistorySpamStatsUiState.Error(it.message ?: "Unknown error")
                }
            )
        }
    }

    fun startHistoricalScan() {
        _isScanProgressHidden.value = false
        _historyScanProgress.value = null
        observedScanId = HistoricalSpamScanWorker.schedule(appContext)
    }

    fun hideScanProgress() {
        if (_historyScanProgress.value?.isFinished == true) {
            _isScanProgressHidden.value = true
        }
    }

    fun clearHistoryStats() {
        _historyStatsState.value = HistorySpamStatsUiState.Idle
    }

    private fun fetchNextBatch(
        limit: Int,
        showError: Boolean = true,
        fallbackSnapshot: SpamConversationListSnapshot? = null,
    ) {
        val requestVersion = listRequestVersion
        isLoadingMore = true
        listJob = viewModelScope.launch {
            try {
                repository.getSpamConversations(limit, offset)
                    .catch { e ->
                        if (requestVersion != listRequestVersion) return@catch
                        if (fallbackSnapshot != null) {
                            conversations.clear()
                            conversations.addAll(fallbackSnapshot.conversations)
                            offset = fallbackSnapshot.offset
                            hasMore = fallbackSnapshot.hasMore
                            _uiState.value =
                                SpamConversationListUiState.Success(conversations.toList())
                        } else if (showError && conversations.isEmpty()) {
                            _uiState.value =
                                SpamConversationListUiState.Error(e.message ?: "Unknown error")
                        }
                        isLoadingMore = false
                    }
                    .collect { newList ->
                        if (requestVersion != listRequestVersion) return@collect
                        if (newList.isEmpty()) {
                            hasMore = false
                            if (conversations.isEmpty()) {
                                _uiState.value = SpamConversationListUiState.Success(emptyList())
                            }
                        } else {
                            mergeConversations(newList)
                            offset += newList.size
                            _uiState.value =
                                SpamConversationListUiState.Success(conversations.toList())
                        }
                        isLoadingMore = false
                    }
            } finally {
                if (requestVersion == listRequestVersion) {
                    isLoadingMore = false
                    if (silentRefreshPending) {
                        silentRefreshPending = false
                        loadSpamConversations(force = true, showLoading = false)
                    }
                }
            }
        }
    }

    private fun mergeConversations(newList: List<ConversationModel>) {
        val merged = (conversations + newList)
            .associateBy { it.threadId }
            .values
            .sortedByDescending { it.timestamp }

        conversations.clear()
        conversations.addAll(merged)
    }

    private fun observeHistoricalScans() {
        viewModelScope.launch {
            var lastFinishedId: UUID? = null
            // 持续观察唯一任务链，排队落库或维护任务替换扫描时不丢失后续状态。
            workManager.getWorkInfosForUniqueWorkFlow(HistoricalSpamScanWorker.UNIQUE_WORK_NAME)
                .collect { workInfos ->
                    val workInfo = workInfos.firstOrNull { !it.state.isFinished }
                        ?: workInfos.firstOrNull { it.id == observedScanId }
                        // REPLACE/KEEP 链只有一个当前任务；替换后可能直接收到它的终态。
                        ?: workInfos.singleOrNull()?.takeIf { observedScanId != null }
                    if (workInfo == null) {
                        _historyScanProgress.value = null
                        return@collect
                    }
                    val changedTask = observedScanId != workInfo.id
                    observedScanId = workInfo.id
                    if (changedTask || !workInfo.state.isFinished) {
                        _isScanProgressHidden.value = false
                    }
                    val data = if (workInfo.state.isFinished) workInfo.outputData else workInfo.progress
                    val total = data.getInt(HistoricalSpamScanWorker.KEY_TOTAL, 0)
                    val processed = data.getInt(HistoricalSpamScanWorker.KEY_PROCESSED, 0)
                    val phase = when (workInfo.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                            if (workInfo.runAttemptCount > 0) HistorySpamScanPhase.RETRYING
                            else HistorySpamScanPhase.WAITING
                        WorkInfo.State.RUNNING -> when {
                            !data.keyValueMap.containsKey(HistoricalSpamScanWorker.KEY_TOTAL) ->
                                HistorySpamScanPhase.PREPARING
                            total <= 0 || processed >= total -> HistorySpamScanPhase.FINALIZING
                            else -> HistorySpamScanPhase.SCANNING
                        }
                        WorkInfo.State.SUCCEEDED -> HistorySpamScanPhase.COMPLETED
                        WorkInfo.State.FAILED -> HistorySpamScanPhase.FAILED
                        WorkInfo.State.CANCELLED -> HistorySpamScanPhase.CANCELLED
                    }
                    val progress = HistorySpamScanProgress(
                        processed = processed,
                        total = total,
                        spamCount = data.getInt(HistoricalSpamScanWorker.KEY_SPAM_COUNT, 0),
                        phase = phase,
                    )
                    // 没有待识别短信的后台补扫结束后，不留下空的完成横幅。
                    _historyScanProgress.value = progress.takeUnless { it.isCompleted && total == 0 }
                    if (workInfo.state.isFinished && lastFinishedId != workInfo.id) {
                        lastFinishedId = workInfo.id
                        loadHistoryStats()
                        loadSpamConversations(force = true)
                    }
                }
        }
    }
}

private data class SpamConversationListSnapshot(
    val conversations: List<ConversationModel>,
    val offset: Int,
    val hasMore: Boolean,
)

sealed class SpamConversationListUiState {
    data object Loading : SpamConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) : SpamConversationListUiState()
    data class Error(val message: String) : SpamConversationListUiState()
}

data class HistorySpamStats(
    val totalCount: Int,
    val identifiedCount: Int
) {
    val pendingCount: Int
        get() = (totalCount - identifiedCount).coerceAtLeast(0)
}

sealed class HistorySpamStatsUiState {
    data object Idle : HistorySpamStatsUiState()
    data object Loading : HistorySpamStatsUiState()
    data class Success(val stats: HistorySpamStats) : HistorySpamStatsUiState()
    data class Error(val message: String) : HistorySpamStatsUiState()
}

enum class HistorySpamScanPhase {
    WAITING, RETRYING, PREPARING, SCANNING, FINALIZING, COMPLETED, FAILED, CANCELLED
}

data class HistorySpamScanProgress(
    val processed: Int,
    val total: Int,
    val spamCount: Int,
    val phase: HistorySpamScanPhase,
) {
    val isCompleted: Boolean get() = phase == HistorySpamScanPhase.COMPLETED
    val isFinished: Boolean get() = isCompleted || phase == HistorySpamScanPhase.FAILED ||
        phase == HistorySpamScanPhase.CANCELLED

    // 未取得总数、等待重试和收尾时使用不定进度，不能把 0/0 当作 100%。
    val fraction: Float?
        get() = when {
            isCompleted -> 1f
            phase == HistorySpamScanPhase.SCANNING && total > 0 ->
                (processed.toFloat() / total).coerceIn(0f, 1f)
            else -> null
        }

    val percent: Int? get() = fraction?.let { (it * 100).toInt() }
}
