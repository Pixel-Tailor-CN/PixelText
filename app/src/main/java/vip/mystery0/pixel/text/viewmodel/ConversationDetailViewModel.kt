package vip.mystery0.pixel.text.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.repository.ConversationContentFilter
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration
import vip.mystery0.pixel.text.worker.SpamDetectionWorker
import kotlin.time.Duration.Companion.milliseconds

/**
 * 单次性的发送结果事件，供 UI 用 Snackbar 等方式提示用户。
 */
sealed interface SendResultEvent {
    data class Submitted(val text: String) : SendResultEvent
    data class Failure(val reason: String) : SendResultEvent
}

sealed interface DeleteMessageResultEvent {
    data class Success(val count: Int) : DeleteMessageResultEvent
    data class Failure(val reason: String) : DeleteMessageResultEvent
}

sealed interface ManualSpamCheckState {
    data object Checking : ManualSpamCheckState
    data class Result(val score: Float) : ManualSpamCheckState
    data class Error(val message: String) : ManualSpamCheckState
}

class ConversationDetailViewModel(
    private val repository: MessageRepository,
    private val telephonyDataSource: TelephonyDataSource,
    private val contactDataSource: ContactDataSource,
    private val context: Context,
    private val senderProfileRepository: vip.mystery0.pixel.text.data.repository.SenderProfileRepository,
    private val spamClassifierFactory: SpamClassifierFactory,
    private val spamRepository: SpamRepository,
    private val mirror: vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository,
    private val whitelist: vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository,
    private val smsSender: vip.mystery0.pixel.text.sms.SmsSendCoordinator,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
    val uiState: StateFlow<MessageUiState> = _uiState.asStateFlow()

    private val _address = MutableStateFlow<String>("")
    val address: StateFlow<String> = _address.asStateFlow()
    private val _conversationTitle = MutableStateFlow("")
    val conversationTitle: StateFlow<String> = _conversationTitle.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _sendResultEvents = Channel<SendResultEvent>(Channel.BUFFERED)
    val sendResultEvents = _sendResultEvents.receiveAsFlow()

    private val _deleteMessageResultEvents =
        Channel<DeleteMessageResultEvent>(Channel.BUFFERED)
    val deleteMessageResultEvents = _deleteMessageResultEvents.receiveAsFlow()

    private val _manualSpamChecks = MutableStateFlow<Map<Long, ManualSpamCheckState>>(emptyMap())
    val manualSpamChecks: StateFlow<Map<Long, ManualSpamCheckState>> =
        _manualSpamChecks.asStateFlow()

    private val _newMessageKeys = MutableStateFlow<Set<String>>(emptySet())
    val newMessageKeys: StateFlow<Set<String>> = _newMessageKeys.asStateFlow()

    private var currentThreadId: Long = -1L
    private var currentContentFilter = ConversationContentFilter.ALL
    private var offset = 0
    private var hasMore = true
    private var loadVersion = 0
    private var messageLoadJob: Job? = null
    private var activeLoadIsRefresh = false
    private var pendingPageSize = 0
    private var pendingRefresh: RefreshRequest? = null
    private var loadFailureCount = 0L

    private data class RefreshRequest(
        val preserveLoadedHistory: Boolean,
        val insertionBaseline: Set<String>?,
    ) {
        fun merge(other: RefreshRequest) = RefreshRequest(
            preserveLoadedHistory || other.preserveLoadedHistory,
            when {
                insertionBaseline == null -> other.insertionBaseline
                other.insertionBaseline == null -> insertionBaseline
                else -> insertionBaseline intersect other.insertionBaseline
            },
        )
    }
    private val _messages = mutableListOf<MessageModel>()
    private val manualClassificationMutex = Mutex()
    private val spamDetectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context?, intent: Intent?) {
            if (intent?.action != SpamDetectionWorker.ACTION_SPAM_DETECTED) return
            val threadId = intent.getLongExtra(SpamDetectionWorker.KEY_THREAD_ID, -1L)
            if (threadId == currentThreadId) {
                refreshMessages()
            }
        }
    }

    init {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(
            context,
            spamDetectionReceiver,
            IntentFilter(SpamDetectionWorker.ACTION_SPAM_DETECTED),
            flags
        )
    }

    private val mirrorThreadId = MutableStateFlow(-1L)
    private var mirrorObservationJob: Job? = null
    private var reading = false
    private val readRequested = mutableSetOf<Long>()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    fun startObservingTelephony(): Boolean {
        reading = true
        markDisplayedMessagesRead(_messages)
        if (mirrorObservationJob?.isActive == true) return true
        mirrorObservationJob = viewModelScope.launch {
            var initialSnapshot = true
            merge(
                mirrorThreadId.flatMapLatest { mirror.observeThreadChanges(it) }.map { true },
                spamRepository.observeChanges().debounce(100.milliseconds).map { false },
            ).collect { fromMirror ->
                // 白名单和关键词变更也刷新详情，但不触发新消息入场或自动滚动。
                if (!fromMirror) _manualSpamChecks.value = emptyMap()
                if (currentThreadId >= 0) refreshMessages(reportInsertions = fromMirror && !initialSnapshot)
                if (fromMirror) initialSnapshot = false
            }
        }
        return true
    }

    fun stopObservingTelephony(): Boolean {
        reading = false
        mirrorObservationJob?.cancel()
        mirrorObservationJob = null
        return true
    }
    fun loadThread(
        threadId: Long,
        address: String,
        contentFilter: ConversationContentFilter = ConversationContentFilter.ALL,
    ) {
        _address.value = address
        _conversationTitle.value = address
        viewModelScope.launch {
            val title = resolveConversationTitle(address)
            if (_address.value == address) _conversationTitle.value = title
        }
        if (currentThreadId == threadId &&
            currentContentFilter == contentFilter &&
            (_messages.isNotEmpty() || messageLoadJob != null || _uiState.value is MessageUiState.Success)
        ) {
            return
        }

        // 只有会话或筛选上下文重置才让已有结果失效，普通通知只合并补刷。
        loadVersion++
        messageLoadJob?.cancel()
        messageLoadJob = null
        pendingRefresh = null
        pendingPageSize = 0
        currentThreadId = threadId
        mirrorThreadId.value = threadId
        currentContentFilter = contentFilter
        _messages.clear()
        readRequested.clear()
        _newMessageKeys.value = emptySet()
        offset = 0
        hasMore = true

        if (threadId == -1L) {
            // 新会话，没有历史消息
            _uiState.value = MessageUiState.Success(emptyList())
        } else {
            _uiState.value = MessageUiState.Loading
            fetchMessages()
        }
    }

    private suspend fun resolveConversationTitle(address: String): String = withContext(Dispatchers.IO) {
        contactDataSource.getDisplayName(address)?.takeIf { it.isNotBlank() }
            ?: senderProfileRepository.findByNumber(address)?.displayName
            ?: address
    }

    fun loadMore() {
        fetchMessages()
    }

    fun consumeNewMessageKeys(keys: Set<String>) {
        _newMessageKeys.value = _newMessageKeys.value - keys
    }

    suspend fun loadUntilMessage(messageId: Long): Int? {
        val generation = loadVersion
        val failures = loadFailureCount
        while (generation == loadVersion && currentThreadId >= 0) {
            val targetIndex = _messages.indexOfFirst { it.id == messageId }
            if (targetIndex >= 0) return targetIndex
            if (!hasMore || loadFailureCount != failures) return null

            // 等待本次加载即可；持续到来的刷新不能把定位操作一直留在 busy 循环中。
            if (messageLoadJob == null || activeLoadIsRefresh) fetchMessages(TARGET_MESSAGE_PAGE_SIZE)
            messageLoadJob?.join()
        }
        return null
    }

    private fun fetchMessages(pageSize: Int = MESSAGE_PAGE_SIZE) {
        if (!hasMore || currentThreadId < 0) return
        if (messageLoadJob != null) {
            // 正在翻页时重复的触底事件不再排一页；刷新期间的翻页需求保留。
            if (activeLoadIsRefresh) pendingPageSize = maxOf(pendingPageSize, pageSize)
            return
        }
        startMessageLoad(pageSize = pageSize)
    }

    private fun startMessageLoad(
        refresh: RefreshRequest? = null,
        pageSize: Int = MESSAGE_PAGE_SIZE,
    ) {
        val generation = loadVersion
        val threadId = currentThreadId
        val filter = currentContentFilter
        val requestedOffset = if (refresh == null) offset else 0
        val requestedLimit = if (refresh == null) pageSize else {
            _messages.size.coerceAtLeast(MESSAGE_PAGE_SIZE) +
                    if (refresh.preserveLoadedHistory) MESSAGE_PAGE_SIZE else 0
        }
        val source = if (refresh == null) "page" else "refresh"
        activeLoadIsRefresh = refresh != null
        // 先保存任务再启动，避免立即返回的仓库在 finally 清理后又留下已完成任务。
        messageLoadJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val started = SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "message load started source=$source generation=$generation offset=$requestedOffset limit=$requestedLimit"
            )
            try {
                val rows = repository.getMessagesByThread(
                    threadId, requestedLimit, requestedOffset, filter,
                ).first()
                if (generation != loadVersion) return@launch
                if (refresh == null) {
                    val existingKeys = _messages.mapTo(mutableSetOf()) { it.stableKey }
                    val added = rows.filter { it.stableKey !in existingKeys }
                    _messages.addAll(added)
                    offset += rows.size
                    hasMore = rows.size >= requestedLimit && added.isNotEmpty()
                    if (requestedOffset > 0) {
                        // 补刷等待期间翻到的旧消息不属于新插入，不能触发入场提示。
                        pendingRefresh?.let { pending ->
                            pendingRefresh = pending.copy(
                                insertionBaseline = pending.insertionBaseline?.plus(
                                    rows.map { it.stableKey },
                                ),
                            )
                        }
                    }
                } else {
                    // 扩大查询窗口保留已加载历史，但不拼回已删除或被筛掉的旧对象。
                    val keys = rows.mapTo(mutableSetOf()) { it.stableKey }
                    _newMessageKeys.value = _newMessageKeys.value intersect keys
                    refresh.insertionBaseline?.let { baseline ->
                        _newMessageKeys.value += keys - baseline
                    }
                    _messages.clear()
                    _messages.addAll(rows)
                    offset = rows.size
                    hasMore = rows.size >= requestedLimit
                }
                _uiState.value = MessageUiState.Success(_messages.toList())
                markDisplayedMessagesRead(_messages)
                if (BuildConfig.DEBUG) Log.d(
                    TAG,
                    "message load published source=$source generation=$generation count=${_messages.size} elapsed_ms=${SystemClock.elapsedRealtime() - started}"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == loadVersion) {
                    loadFailureCount++
                    if (_messages.isEmpty()) {
                        _uiState.value = MessageUiState.Error(error.message ?: "加载失败，请重试")
                    }
                    Log.w(
                        TAG,
                        "message load failed source=$source generation=$generation error=${error.javaClass.simpleName}"
                    )
                }
            } finally {
                if (generation == loadVersion) {
                    messageLoadJob = null
                    if (currentCoroutineContext().isActive) drainPendingLoads()
                }
            }
        }
        messageLoadJob?.start()
    }

    private fun drainPendingLoads() {
        // 先兑现用户翻页，避免全局评分持续变化导致翻页饿死。
        val nextPageSize = pendingPageSize
        pendingPageSize = 0
        if (nextPageSize > 0 && hasMore) {
            startMessageLoad(pageSize = nextPageSize)
            return
        }
        val refresh = pendingRefresh ?: return
        pendingRefresh = null
        startMessageLoad(refresh)
    }

    /**
     * @param subId 指定发送使用的 SIM 卡。传 [SubscriptionManager.INVALID_SUBSCRIPTION_ID] 表示使用默认 SIM。
     */
    fun sendMessage(
        address: String,
        message: String,
        subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    ) {
        if (_sending.value) return
        _sending.value = true

        viewModelScope.launch {
            try {
                val id = smsSender.send(address, message, subId, currentThreadId)
                val pendingUri = android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
                val resolvedThreadId = runCatching { telephonyDataSource.queryThreadIdFromUri(pendingUri) }.getOrNull() ?: currentThreadId
                if (currentThreadId == -1L && resolvedThreadId != -1L) {
                    loadThread(resolvedThreadId, address, currentContentFilter)
                }

                _sendResultEvents.trySend(SendResultEvent.Submitted(message))
                refreshMessages(preserveLoadedHistory = true, reportInsertions = true)
            } catch (e: Exception) {
                _sendResultEvents.trySend(SendResultEvent.Failure(e.message ?: "未知错误"))
            } finally {
                _sending.value = false
            }
        }
    }

    fun deleteMessages(messageIds: Set<Long>) {
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.deleteMessages(messageIds)
            }.onSuccess { deletedCount ->
                _manualSpamChecks.value = _manualSpamChecks.value - messageIds
                refreshMessages()
                _deleteMessageResultEvents.trySend(
                    DeleteMessageResultEvent.Success(deletedCount)
                )
            }.onFailure { e ->
                _deleteMessageResultEvents.trySend(
                    DeleteMessageResultEvent.Failure(e.message ?: "删除失败")
                )
            }
        }
    }

    fun checkSpamOnce(message: MessageModel) {
        if (message.content.isBlank()) {
            _manualSpamChecks.value += (message.id to ManualSpamCheckState.Error("没有可检测的文本"))
            return
        }
        if (message.spamScore >= 0f) {
            _manualSpamChecks.value += (message.id to ManualSpamCheckState.Error("已有识别记录"))
            return
        }
        if (_manualSpamChecks.value[message.id] is ManualSpamCheckState.Checking) return

        _manualSpamChecks.value += (message.id to ManualSpamCheckState.Checking)
        viewModelScope.launch {
            val result = runCatching {
                if (whitelist.isAllowed(message.id)) return@runCatching 0f
                withContext(Dispatchers.Default) {
                    manualClassificationMutex.withLock {
                        spamClassifierFactory.create().use { classifier ->
                            val prediction = classifier.predict(message.content)
                            check(spamRepository.savePrediction(message.id, message.threadId, prediction, vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey.create(
                                if (message.isMms) "MMS" else "SMS", message.id, message.sender, message.timestamp, message.content), allowDisabled = true)) { "模型已更新，请重新检测" }
                            prediction.score
                        }
                    }
                }
            }
            val state = if (result.isSuccess) {
                val score = result.getOrThrow()
                if (score >= 0f) {
                    SmartspacerIntegration.notifyChanged(context)
                    val effectiveScore = spamRepository.getScore(message.id) ?: score
                    updateMessageSpamScore(message.id, effectiveScore)
                    ManualSpamCheckState.Result(effectiveScore)
                } else {
                    ManualSpamCheckState.Error("识别失败")
                }
            } else {
                ManualSpamCheckState.Error(result.exceptionOrNull()?.message ?: "识别失败")
            }
            _manualSpamChecks.value += (message.id to state)
        }
    }

    private fun updateMessageSpamScore(messageId: Long, score: Float) {
        val index = _messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val isSpam = score >= SPAM_THRESHOLD
        if (!currentContentFilter.includes(isSpam)) {
            _messages.removeAt(index)
            offset = (offset - 1).coerceAtLeast(0)
        } else {
            _messages[index] = _messages[index].copy(spamScore = score)
        }
        _uiState.value = MessageUiState.Success(_messages.toList())
    }

    private fun ConversationContentFilter.includes(isSpam: Boolean): Boolean = when (this) {
        ConversationContentFilter.ALL -> true
        ConversationContentFilter.NORMAL -> !isSpam
        ConversationContentFilter.SPAM -> isSpam
    }

    private fun refreshMessages(
        preserveLoadedHistory: Boolean = false,
        reportInsertions: Boolean = false,
    ) {
        if (currentThreadId < 0) return
        val request = RefreshRequest(
            preserveLoadedHistory,
            if (reportInsertions) _messages.mapTo(mutableSetOf()) { it.stableKey } else null,
        )
        if (messageLoadJob != null) {
            val alreadyQueued = pendingRefresh != null
            pendingRefresh = pendingRefresh?.merge(request) ?: request
            if (BuildConfig.DEBUG && !alreadyQueued) Log.d(
                TAG,
                "message load queued source=refresh generation=$loadVersion"
            )
        } else {
            startMessageLoad(request)
        }
    }

    private fun markDisplayedMessagesRead(messages: List<MessageModel>) {
        if (!reading) return
        readRequested.removeAll(messages.filter { it.isRead }.mapTo(mutableSetOf()) { it.id })
        val ids = messages.filter { it.isReceived && !it.isRead && it.id !in readRequested }
            .mapTo(mutableSetOf()) { it.id }
        if (ids.isEmpty()) return
        readRequested += ids
        viewModelScope.launch {
            runCatching { repository.markMessagesAsRead(ids) }.onFailure {
                readRequested -= ids
                Log.w(TAG, "mark displayed messages read failed", it)
            }
        }
    }

    override fun onCleared() {
        runCatching { stopObservingTelephony() }
        runCatching { context.unregisterReceiver(spamDetectionReceiver) }
        super.onCleared()
    }

    companion object {
        private const val TAG = "ConversationDetailViewM"
        private const val SPAM_THRESHOLD = 0.7f
        private const val MESSAGE_PAGE_SIZE = 20
        private const val TARGET_MESSAGE_PAGE_SIZE = 200
    }
}
