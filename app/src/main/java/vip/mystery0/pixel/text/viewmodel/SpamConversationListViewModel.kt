package vip.mystery0.pixel.text.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.worker.HistoricalSpamScanWorker
import java.util.UUID

class SpamConversationListViewModel(
    private val repository: MessageRepository,
    private val telephonyDataSource: TelephonyDataSource,
    private val spamRepository: SpamRepository,
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

    private val conversations = mutableListOf<ConversationModel>()
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private var offset = 0
    private var isLoadingMore = false
    private var hasMore = true
    private var scanProgressJob: Job? = null

    init {
        resumeActiveHistoricalScan()
    }

    fun loadSpamConversations(force: Boolean = false) {
        if (force) {
            offset = 0
            conversations.clear()
            hasMore = true
            _uiState.value = SpamConversationListUiState.Loading
        } else if (conversations.isNotEmpty()) {
            return
        }

        fetchNextBatch(100)
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
                    val messages = telephonyDataSource.getSmsMessagesForSpamScan()
                    val identifiedIds =
                        spamRepository.getIdentifiedMessageIds(messages.map { it.messageId })
                    HistorySpamStats(
                        totalCount = messages.size,
                        identifiedCount = identifiedIds.size
                    )
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
        val workId = HistoricalSpamScanWorker.schedule(appContext)
        observeHistoricalScan(workId)
    }

    fun clearHistoryStats() {
        _historyStatsState.value = HistorySpamStatsUiState.Idle
    }

    private fun fetchNextBatch(limit: Int) {
        isLoadingMore = true
        viewModelScope.launch {
            repository.getSpamConversations(limit, offset)
                .catch { e ->
                    if (conversations.isEmpty()) {
                        _uiState.value =
                            SpamConversationListUiState.Error(e.message ?: "Unknown error")
                    }
                    isLoadingMore = false
                }
                .collect { newList ->
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
        }
    }

    private fun mergeConversations(newList: List<ConversationModel>) {
        val currentByThreadId = conversations.associateBy { it.threadId }
        val merged = (conversations + newList)
            .distinctBy { it.threadId }
            .map { conversation ->
                currentByThreadId[conversation.threadId]?.let { existing ->
                    if (conversation.timestamp >= existing.timestamp) conversation else existing
                } ?: conversation
            }
            .sortedByDescending { it.timestamp }

        conversations.clear()
        conversations.addAll(merged)
    }

    private fun resumeActiveHistoricalScan() {
        viewModelScope.launch {
            val activeWorkId = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(HistoricalSpamScanWorker.UNIQUE_WORK_NAME)
                    .get()
                    .firstOrNull { it.state.isActiveScanState() }
                    ?.id
            }
            if (activeWorkId != null) {
                observeHistoricalScan(activeWorkId)
            }
        }
    }

    private fun observeHistoricalScan(workId: UUID) {
        scanProgressJob?.cancel()
        scanProgressJob = viewModelScope.launch {
            while (isActive) {
                val workInfo = withContext(Dispatchers.IO) {
                    workManager.getWorkInfoById(workId).get()
                } ?: break

                val data = if (workInfo.state.isFinishedState()) {
                    workInfo.outputData
                } else {
                    workInfo.progress
                }
                val total = data.getInt(HistoricalSpamScanWorker.KEY_TOTAL, 0)
                val processed = data.getInt(HistoricalSpamScanWorker.KEY_PROCESSED, 0)
                val spamCount = data.getInt(HistoricalSpamScanWorker.KEY_SPAM_COUNT, 0)

                _historyScanProgress.value = HistorySpamScanProgress(
                    processed = processed,
                    total = total,
                    spamCount = spamCount,
                    isRunning = workInfo.state.isActiveScanState(),
                    isCompleted = workInfo.state == WorkInfo.State.SUCCEEDED,
                    isFailed = workInfo.state == WorkInfo.State.FAILED ||
                        workInfo.state == WorkInfo.State.CANCELLED
                )

                if (workInfo.state.isFinishedState()) {
                    loadHistoryStats()
                    loadSpamConversations(force = true)
                    break
                }
                delay(800)
            }
        }
    }

    private fun WorkInfo.State.isActiveScanState(): Boolean {
        return this == WorkInfo.State.ENQUEUED ||
            this == WorkInfo.State.RUNNING ||
            this == WorkInfo.State.BLOCKED
    }

    private fun WorkInfo.State.isFinishedState(): Boolean {
        return this == WorkInfo.State.SUCCEEDED ||
            this == WorkInfo.State.FAILED ||
            this == WorkInfo.State.CANCELLED
    }
}

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

data class HistorySpamScanProgress(
    val processed: Int,
    val total: Int,
    val spamCount: Int,
    val isRunning: Boolean,
    val isCompleted: Boolean,
    val isFailed: Boolean
) {
    val percent: Int
        get() = if (total <= 0) 100 else ((processed.toFloat() / total) * 100)
            .toInt()
            .coerceIn(0, 100)

    val fraction: Float
        get() = if (total <= 0) 1f else (processed.toFloat() / total).coerceIn(0f, 1f)
}
