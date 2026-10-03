package vip.mystery0.pixel.text.viewmodel

import kotlin.time.Duration.Companion.milliseconds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.repository.ConversationContentFilter
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration
import vip.mystery0.pixel.text.worker.SpamDetectionWorker

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

sealed interface MarkSpamResultEvent {
    data class Success(val markedAsSpam: Boolean) : MarkSpamResultEvent
    data class Failure(val reason: String) : MarkSpamResultEvent
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

    private val _markSpamResultEvents = Channel<MarkSpamResultEvent>(Channel.BUFFERED)
    val markSpamResultEvents = _markSpamResultEvents.receiveAsFlow()

    private val _manualSpamChecks = MutableStateFlow<Map<Long, ManualSpamCheckState>>(emptyMap())
    val manualSpamChecks: StateFlow<Map<Long, ManualSpamCheckState>> =
        _manualSpamChecks.asStateFlow()

    private val _newMessageKeys = MutableStateFlow<Set<String>>(emptySet())
    val newMessageKeys: StateFlow<Set<String>> = _newMessageKeys.asStateFlow()

    private var currentThreadId: Long = -1L
    private var currentContentFilter = ConversationContentFilter.ALL
    private var offset = 0
    private var isLoadingMore = false
    private var hasMore = true
    private var loadVersion = 0
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
            _conversationTitle.value = resolveConversationTitle(address)
        }
        if (currentThreadId == threadId &&
            currentContentFilter == contentFilter &&
            _messages.isNotEmpty()
        ) {
            return
        }

        currentThreadId = threadId
        mirrorThreadId.value = threadId
        currentContentFilter = contentFilter
        _messages.clear()
        readRequested.clear()
        _newMessageKeys.value = emptySet()
        offset = 0
        isLoadingMore = false
        hasMore = true
        loadVersion++

        if (threadId == -1L) {
            // 新会话，没有历史消息
            _uiState.value = MessageUiState.Success(emptyList())
        } else {
            _uiState.value = MessageUiState.Loading
            fetchMessages()
        }
    }

    private suspend fun resolveConversationTitle(address: String): String {
        return contactDataSource.getDisplayName(address)?.takeIf { it.isNotBlank() }
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
        while (true) {
            val targetIndex = _messages.indexOfFirst { it.id == messageId }
            if (targetIndex >= 0) return targetIndex
            if (!hasMore) return null

            val previousSize = _messages.size
            if (!isLoadingMore) fetchMessages()
            while (isLoadingMore) delay(MESSAGE_LOAD_POLL_INTERVAL_MILLIS.milliseconds)

            if (_messages.size == previousSize) return null
        }
    }

    private fun fetchMessages() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        val requestVersion = loadVersion
        viewModelScope.launch {
            repository.getMessagesByThread(
                currentThreadId,
                MESSAGE_PAGE_SIZE,
                offset,
                currentContentFilter,
            )
                .catch { e ->
                    if (requestVersion != loadVersion) return@catch
                    if (_messages.isEmpty()) {
                        _uiState.value = MessageUiState.Error(e.message ?: "Unknown error")
                    }
                    isLoadingMore = false
                }
                .collect { newMessages ->
                    if (requestVersion != loadVersion) return@collect
                    if (newMessages.isEmpty()) {
                        hasMore = false
                        if (_messages.isEmpty()) {
                            _uiState.value = MessageUiState.Success(emptyList())
                        }
                    } else {
                        val existingKeys = _messages.map { it.stableKey }.toSet()
                        val deduplicated = newMessages.filter { it.stableKey !in existingKeys }
                        if (deduplicated.isEmpty()) {
                            hasMore = false
                        } else {
                            _messages.addAll(deduplicated)
                            offset += newMessages.size
                        }
                        _uiState.value = MessageUiState.Success(_messages.toList())
                        markDisplayedMessagesRead(_messages)
                    }
                    isLoadingMore = false
                }
        }
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
                    currentThreadId = resolvedThreadId
                    mirrorThreadId.value = resolvedThreadId
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

    fun markMessageSpam(message: MessageModel, markedAsSpam: Boolean) {
        viewModelScope.launch {
            runCatching {
                val score = if (markedAsSpam) MANUAL_SPAM_SCORE else MANUAL_NON_SPAM_SCORE
                spamRepository.save(message.id, message.threadId, score)
                SmartspacerIntegration.notifyChanged(context)
                val effectiveScore = spamRepository.getScore(message.id) ?: score
                updateMessageSpamScore(message.id, effectiveScore)
                _manualSpamChecks.value += (message.id to ManualSpamCheckState.Result(effectiveScore))
                effectiveScore >= SPAM_THRESHOLD
            }.onSuccess { isSpam ->
                _markSpamResultEvents.trySend(MarkSpamResultEvent.Success(isSpam))
            }.onFailure { e ->
                _markSpamResultEvents.trySend(
                    MarkSpamResultEvent.Failure(e.message ?: "标记失败")
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
                            classifier.classify(message.content)
                        }
                    }
                }
            }
            val state = if (result.isSuccess) {
                val score = result.getOrThrow()
                if (score >= 0f) {
                    spamRepository.save(message.id, message.threadId, score)
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
        val existingMessages = _messages.toList()
        val requestedLimit = if (preserveLoadedHistory) {
            existingMessages.size.coerceAtLeast(MESSAGE_PAGE_SIZE) + MESSAGE_PAGE_SIZE
        } else {
            existingMessages.size.coerceAtLeast(MESSAGE_PAGE_SIZE)
        }
        val requestVersion = ++loadVersion
        isLoadingMore = true
        viewModelScope.launch {
            repository.getMessagesByThread(
                currentThreadId,
                requestedLimit,
                0,
                currentContentFilter,
            )
                .catch { error ->
                    if (requestVersion != loadVersion) return@catch
                    if (_messages.isEmpty()) {
                        _uiState.value = MessageUiState.Error(error.message ?: "Unknown error")
                    }
                    isLoadingMore = false
                }
                .collect { refreshedMessages ->
                    if (requestVersion != loadVersion) return@collect
                    val refreshedKeys = refreshedMessages.mapTo(mutableSetOf()) { it.stableKey }
                    val reconciledMessages = if (preserveLoadedHistory) {
                        refreshedMessages + existingMessages.filter { it.stableKey !in refreshedKeys }
                    } else {
                        refreshedMessages
                    }
                    _newMessageKeys.value = _newMessageKeys.value intersect
                        reconciledMessages.mapTo(mutableSetOf()) { it.stableKey }
                    if (reportInsertions) {
                        val existingKeys = existingMessages.mapTo(mutableSetOf()) { it.stableKey }
                        _newMessageKeys.value += refreshedKeys - existingKeys
                    }
                    _messages.clear()
                    _messages.addAll(reconciledMessages)
                    offset = reconciledMessages.size
                    hasMore = if (preserveLoadedHistory) {
                        hasMore || refreshedMessages.size >= requestedLimit
                    } else {
                        refreshedMessages.size >= requestedLimit
                    }
                    _uiState.value = MessageUiState.Success(_messages.toList())
                    markDisplayedMessagesRead(_messages)
                    isLoadingMore = false
                }
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
        private const val MANUAL_SPAM_SCORE = 1f
        private const val MANUAL_NON_SPAM_SCORE = 0f
        private const val MESSAGE_PAGE_SIZE = 20
        private const val MESSAGE_LOAD_POLL_INTERVAL_MILLIS = 16L
    }
}
