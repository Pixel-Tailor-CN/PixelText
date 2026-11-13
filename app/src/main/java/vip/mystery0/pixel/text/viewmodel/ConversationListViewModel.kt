package vip.mystery0.pixel.text.viewmodel

import kotlin.time.Duration.Companion.milliseconds

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import vip.mystery0.pixel.text.domain.model.DataInitializationState
import vip.mystery0.pixel.text.data.repository.initialization.DataInitializationRepository
import vip.mystery0.pixel.text.worker.DataInitializationScheduler
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.util.AppStrings

private const val TAG = "ConversationListVM"

class ConversationListViewModel(
    private val repository: MessageRepository,
    settingsRepository: AppSettingsRepository,
    initializationRepository: DataInitializationRepository,
    private val initializationScheduler: DataInitializationScheduler
) : ViewModel() {
    private val _uiState =
        MutableStateFlow<ConversationListUiState>(ConversationListUiState.Loading)
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()
    val settings = settingsRepository.settings

    val initializationState = initializationRepository.observe()
        .map<DataInitializationState, DataInitializationState?> { it }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), null,
    )

    val initializationProgress = initializationRepository.observeProgress().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), null,
    )

    fun retryInitialization() = initializationScheduler.retry()

    /** 权限变化后确保初始化已排队；保留等待中的任务，不重置退避。 */
    fun ensureInitialization() = initializationScheduler.checkOnLaunch()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _markAllReadProgress = MutableStateFlow<MarkAllReadProgress?>(null)
    val markAllReadProgress: StateFlow<MarkAllReadProgress?> =
        _markAllReadProgress.asStateFlow()

    private val _markAllReadResultEvents =
        Channel<MarkAllReadResultEvent>(Channel.BUFFERED)
    val markAllReadResultEvents = _markAllReadResultEvents.receiveAsFlow()

    private val allConversations = mutableListOf<ConversationModel>()
    private var latestConversations: List<ConversationModel> = emptyList()
    private val pendingDeletedThreadIds = mutableSetOf<Long>()
    private val pendingArchivedThreadIds = mutableSetOf<Long>()
    private var conversationSubscriptionJob: Job? = null
    private var isLoading = false
    private var isRefreshingLoaded = false

    fun loadConversations(force: Boolean = false) {
        if (isLoading) return
        repository.startCacheObserving()
        if (conversationSubscriptionJob?.isActive == true) {
            if (force && !isRefreshingLoaded) {
                refreshLoaded(showRefreshIndicator = false)
            }
            return
        }
        if (force && allConversations.isEmpty()) {
            _uiState.value = ConversationListUiState.Loading
        }

        loadAllConversations()
    }

    fun refreshOrLoadConversations() {
        if (conversationSubscriptionJob?.isActive != true) {
            loadConversations()
            return
        }
        if (allConversations.isEmpty()) {
            loadConversations(force = true)
        } else {
            refreshSilent()
        }
    }

    fun refreshConversations() {
        if (isLoading || isRefreshingLoaded) return
        refreshLoaded(showRefreshIndicator = true)
    }

    private fun loadAllConversations() {
        isLoading = true
        conversationSubscriptionJob = viewModelScope.launch {
            var retryDelayMillis = INITIAL_SUBSCRIPTION_RETRY_DELAY_MILLIS
            val subscribedAt = android.os.SystemClock.elapsedRealtime()
            var firstPublished = false
            while (true) {
                try {
                    isLoading = allConversations.isEmpty()
                    repository.getAllConversations()
                        .collect { conversations ->
                            // 收到数据后重置退避；订阅随后抛错时，catch 之后的 delay 会读取此值。
                            @Suppress("ASSIGNED_VALUE_IS_NEVER_READ")
                            retryDelayMillis = INITIAL_SUBSCRIPTION_RETRY_DELAY_MILLIS
                            replaceConversations(conversations)
                            isLoading = false
                            if (!firstPublished) {
                                firstPublished = true
                                if (vip.mystery0.pixel.text.BuildConfig.DEBUG) Log.d(
                                    TAG, "conversation first publish count=${conversations.size} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - subscribedAt}",
                                )
                            }
                        }
                    throw IllegalStateException("conversation flow completed")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "conversation subscription failed error=${error::class.java.simpleName}",
                        error,
                    )
                    if (allConversations.isEmpty()) {
                        _uiState.value =
                            ConversationListUiState.Error(error.message ?: AppStrings.get(R.string.sys_unknown_error))
                    }
                } finally {
                    isLoading = false
                }

                delay(retryDelayMillis.milliseconds)
                retryDelayMillis =
                    (retryDelayMillis * 2).coerceAtMost(MAX_SUBSCRIPTION_RETRY_DELAY_MILLIS)
            }
        }
    }

    fun refreshSilent() {
        if (isLoading || isRefreshingLoaded || allConversations.isEmpty()) return
        refreshLoaded(showRefreshIndicator = false)
    }

    private fun refreshLoaded(showRefreshIndicator: Boolean) {
        val started = android.os.SystemClock.elapsedRealtime()
        isRefreshingLoaded = true
        if (showRefreshIndicator) {
            _isRefreshing.value = true
        }
        viewModelScope.launch {
            try {
                // 现有订阅负责发布结果；刷新只推动镜像增量同步，避免另起全量查询。
                repository.refreshConversations()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (showRefreshIndicator) {
                    Log.e(
                        TAG,
                        "conversation refresh failed error=${error::class.java.simpleName}",
                        error,
                    )
                }
            } finally {
                if (vip.mystery0.pixel.text.BuildConfig.DEBUG) Log.d(
                    TAG, "conversation refresh finished visible=$showRefreshIndicator elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}",
                )
                if (showRefreshIndicator) {
                    _isRefreshing.value = false
                }
                isRefreshingLoaded = false
            }
        }
    }

    fun toggleRead(conversation: ConversationModel) {
        viewModelScope.launch {
            runCatching {
                if (conversation.unreadCount > 0) {
                    repository.markThreadAsRead(conversation.threadId)
                    markLoadedConversationsAsRead(setOf(conversation.threadId))
                } else {
                    repository.markThreadAsUnread(conversation.threadId)
                    markLoadedConversationAsUnread(conversation.threadId)
                }
            }.onSuccess {
                refreshSilent()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "toggle read failed thread_id=${conversation.threadId} error=${error.javaClass.simpleName}")
            }
        }
    }

    fun markAllConversationsAsRead() {
        if (_markAllReadProgress.value != null) return
        val unreadThreadIds = allConversations
            .filter { it.unreadCount > 0 }
            .map { it.threadId }
            .toSet()

        if (unreadThreadIds.isEmpty()) {
            _markAllReadResultEvents.trySend(MarkAllReadResultEvent.NoUnread)
            return
        }

        _markAllReadProgress.value =
            MarkAllReadProgress(completed = 0, total = unreadThreadIds.size)
        viewModelScope.launch {
            val completedThreadIds = mutableSetOf<Long>()
            runCatching {
                unreadThreadIds.forEach { threadId ->
                    repository.markThreadAsRead(threadId)
                    completedThreadIds += threadId
                    _markAllReadProgress.value = MarkAllReadProgress(
                        completed = completedThreadIds.size,
                        total = unreadThreadIds.size
                    )
                }
            }.onSuccess {
                markLoadedConversationsAsRead(completedThreadIds)
                _markAllReadResultEvents.trySend(
                    MarkAllReadResultEvent.Success(completedThreadIds.size)
                )
                refreshSilent()
            }.onFailure { e ->
                markLoadedConversationsAsRead(completedThreadIds)
                _markAllReadResultEvents.trySend(
                    MarkAllReadResultEvent.Failure(e.message ?: AppStrings.get(R.string.sys_list_mark_read_failed))
                )
            }
            _markAllReadProgress.value = null
        }
    }

    fun archiveSelected(conversations: List<ConversationModel>) {
        if (conversations.isEmpty()) return
        viewModelScope.launch {
            repository.archiveConversations(conversations)
            loadConversations(force = true)
        }
    }

    fun archiveConversation(conversation: ConversationModel) {
        pendingArchivedThreadIds += conversation.threadId
        removeLoadedConversations(setOf(conversation.threadId))
        viewModelScope.launch {
            runCatching {
                repository.archiveConversations(listOf(conversation))
            }.onSuccess {
                pendingArchivedThreadIds -= conversation.threadId
                refreshSilent()
            }.onFailure {
                pendingArchivedThreadIds -= conversation.threadId
                restoreLoadedConversation(conversation)
            }
        }
    }

    fun deleteSelected(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.deleteThreads(threadIds)
            }.onSuccess {
                pendingDeletedThreadIds.removeAll(threadIds)
                refreshSilent()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                pendingDeletedThreadIds.removeAll(threadIds)
                replaceConversations(latestConversations)
                Log.w(TAG, "delete threads failed count=${threadIds.size} error=${error.javaClass.simpleName}")
            }
        }
    }

    fun hidePendingDelete(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        pendingDeletedThreadIds.addAll(threadIds)
        removeLoadedConversations(threadIds)
    }

    fun restorePendingDelete(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        pendingDeletedThreadIds.removeAll(threadIds)
        replaceConversations(latestConversations)
        refreshSilent()
    }

    private fun replaceConversations(conversations: List<ConversationModel>) {
        latestConversations = conversations
        val visibleList = conversations
            .filterNot {
                it.threadId in pendingDeletedThreadIds ||
                        it.threadId in pendingArchivedThreadIds
            }
            .distinctBy { it.threadId }
        allConversations.clear()
        allConversations.addAll(visibleList)
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private fun removeLoadedConversations(threadIds: Set<Long>) {
        allConversations.removeAll { it.threadId in threadIds }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private fun restoreLoadedConversation(conversation: ConversationModel) {
        allConversations.removeAll { it.threadId == conversation.threadId }
        allConversations += conversation
        allConversations.sortByDescending { it.timestamp }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private fun markLoadedConversationsAsRead(threadIds: Set<Long>) {
        allConversations.replaceAll { conversation ->
            if (conversation.threadId in threadIds) conversation.copy(unreadCount = 0)
            else conversation
        }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private fun markLoadedConversationAsUnread(threadId: Long) {
        allConversations.replaceAll { conversation ->
            if (conversation.threadId == threadId) {
                conversation.copy(unreadCount = conversation.unreadCount.coerceAtLeast(1))
            } else {
                conversation
            }
        }
        _uiState.value = ConversationListUiState.Success(allConversations.toList())
    }

    private companion object {
        const val INITIAL_SUBSCRIPTION_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_SUBSCRIPTION_RETRY_DELAY_MILLIS = 30_000L
    }
}

sealed class ConversationListUiState {
    data object Loading : ConversationListUiState()
    data class Success(val conversations: List<ConversationModel>) : ConversationListUiState()
    data class Error(val message: String) : ConversationListUiState()
}

sealed interface MarkAllReadResultEvent {
    data class Success(val conversationCount: Int) : MarkAllReadResultEvent
    data object NoUnread : MarkAllReadResultEvent
    data class Failure(val reason: String) : MarkAllReadResultEvent
}

data class MarkAllReadProgress(
    val completed: Int,
    val total: Int
) {
    val percent: Int
        get() = if (total <= 0) 0 else (completed * 100 / total).coerceIn(0, 100)
}
