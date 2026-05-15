package vip.mystery0.pixel.text.viewmodel

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.worker.SpamDetectionWorker
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单次性的发送结果事件，供 UI 用 Snackbar 等方式提示用户。
 */
sealed interface SendResultEvent {
    data object Success : SendResultEvent
    data class Failure(val reason: String) : SendResultEvent
}

sealed interface ManualSpamCheckState {
    data object Checking : ManualSpamCheckState
    data class Result(val score: Float) : ManualSpamCheckState
    data class Error(val message: String) : ManualSpamCheckState
}

class ConversationDetailViewModel(
    private val repository: MessageRepository,
    private val context: Context,
    private val spamClassifierFactory: SpamClassifierFactory
) : ViewModel() {
    private val _uiState = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
    val uiState: StateFlow<MessageUiState> = _uiState.asStateFlow()

    private val _address = MutableStateFlow<String>("")
    val address: StateFlow<String> = _address.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _sendResultEvents = Channel<SendResultEvent>(Channel.BUFFERED)
    val sendResultEvents = _sendResultEvents.receiveAsFlow()

    private val _manualSpamChecks = MutableStateFlow<Map<Long, ManualSpamCheckState>>(emptyMap())
    val manualSpamChecks: StateFlow<Map<Long, ManualSpamCheckState>> =
        _manualSpamChecks.asStateFlow()

    private var currentThreadId: Long = -1L
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

    fun loadThread(threadId: Long, address: String) {
        _address.value = address
        if (currentThreadId == threadId && _messages.isNotEmpty()) return

        currentThreadId = threadId
        _messages.clear()
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
            viewModelScope.launch {
                repository.markThreadAsRead(threadId)
            }
        }
    }

    fun loadMore() {
        fetchMessages()
    }

    private fun fetchMessages() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        val requestVersion = loadVersion
        viewModelScope.launch {
            repository.getMessagesByThread(currentThreadId, 20, offset)
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
                // 1. 写入"发件箱"占位（pending），先把 UI 显示出来；获取 thread_id
                val pendingUri = insertOutboxPlaceholder(address, message, subId)
                val resolvedThreadId = queryThreadIdFromUri(pendingUri) ?: currentThreadId
                if (currentThreadId == -1L && resolvedThreadId != -1L) {
                    currentThreadId = resolvedThreadId
                }

                // 2. 立刻把占位插入到 UI 头部，给用户即时反馈
                refreshMessages()

                // 3. 用 PendingIntent 发短信，监听系统返回的发送结果
                val resultCode = sendSmsAndAwaitResult(address, message, subId)

                // 4. 根据结果更新数据库中的那条占位记录
                handleSendResult(pendingUri, resultCode)
                refreshMessages()
            } catch (e: Exception) {
                _sendResultEvents.trySend(SendResultEvent.Failure(e.message ?: "未知错误"))
            } finally {
                _sending.value = false
            }
        }
    }

    fun checkSpamOnce(message: MessageModel) {
        if (message.content.isBlank()) {
            _manualSpamChecks.value =
                _manualSpamChecks.value + (message.id to ManualSpamCheckState.Error("没有可检测的文本"))
            return
        }
        if (_manualSpamChecks.value[message.id] is ManualSpamCheckState.Checking) return

        _manualSpamChecks.value =
            _manualSpamChecks.value + (message.id to ManualSpamCheckState.Checking)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    manualClassificationMutex.withLock {
                        spamClassifierFactory.create().use { classifier ->
                            classifier.classify(message.content)
                        }
                    }
                }
            }
            val state = result.fold(
                onSuccess = { score ->
                    if (score >= 0f) {
                        ManualSpamCheckState.Result(score)
                    } else {
                        ManualSpamCheckState.Error("识别失败")
                    }
                },
                onFailure = { ManualSpamCheckState.Error(it.message ?: "识别失败") }
            )
            _manualSpamChecks.value = _manualSpamChecks.value + (message.id to state)
        }
    }

    /**
     * 在 outbox 中插入占位消息，返回插入后的 URI。
     */
    private fun insertOutboxPlaceholder(
        address: String,
        message: String,
        subId: Int,
    ): Uri? {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, message)
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            if (currentThreadId != -1L) {
                put(Telephony.Sms.THREAD_ID, currentThreadId)
            }
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            }
        }
        return context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
    }

    /**
     * 从插入返回的 URI 读取 thread_id，避免靠 delay 等待系统填值。
     */
    private fun queryThreadIdFromUri(uri: Uri?): Long? {
        if (uri == null) return null
        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.THREAD_ID),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    /**
     * 通过 PendingIntent 发短信并挂起等待 SMS_SENT 广播。
     *
     * @return SmsManager 的发送结果码（RESULT_OK 或其它错误码）
     */
    private suspend fun sendSmsAndAwaitResult(address: String, message: String, subId: Int): Int {
        val requestId = sendRequestCounter.incrementAndGet()
        val sentAction = "$ACTION_SMS_SENT.$requestId"

        return suspendCancellableCoroutine { cont ->
            val baseSmsManager = context.getSystemService(SmsManager::class.java)
            val smsManager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                baseSmsManager.createForSubscriptionId(subId)
            } else {
                baseSmsManager
            }
            val parts = smsManager.divideMessage(message)
            val expectedCount = parts.size.coerceAtLeast(1)
            var receivedCount = 0
            var firstError = Activity.RESULT_OK

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    receivedCount++
                    if (resultCode != Activity.RESULT_OK
                        && firstError == Activity.RESULT_OK
                    ) {
                        firstError = resultCode
                    }
                    // 多段短信收齐所有段后再 resume；单段直接 resume
                    if (receivedCount >= expectedCount) {
                        runCatching { context.unregisterReceiver(this) }
                        if (cont.isActive) cont.resumeWith(Result.success(firstError))
                    }
                }
            }
            val filter = IntentFilter(sentAction)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_NOT_EXPORTED
            } else {
                0
            }
            ContextCompat.registerReceiver(context, receiver, filter, flags)

            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            try {
                if (parts.size > 1) {
                    val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                        repeat(parts.size) {
                            add(buildSentPendingIntent(sentAction, requestId * 100 + it))
                        }
                    }
                    smsManager.sendMultipartTextMessage(
                        address, null, parts, sentIntents, null
                    )
                } else {
                    smsManager.sendTextMessage(
                        address, null, message,
                        buildSentPendingIntent(sentAction, requestId), null
                    )
                }
            } catch (e: Exception) {
                runCatching { context.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resumeWith(Result.success(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
            }
        }
    }

    private fun buildSentPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 根据发送结果，将占位记录从 outbox 升级为 sent，失败则改为 failed。
     */
    private fun handleSendResult(uri: Uri?, resultCode: Int) {
        if (uri == null) return
        val success = resultCode == Activity.RESULT_OK
        val values = ContentValues().apply {
            if (success) {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            } else {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                put(Telephony.Sms.ERROR_CODE, resultCode)
            }
        }
        context.contentResolver.update(uri, values, null, null)

        _sendResultEvents.trySend(
            if (success) SendResultEvent.Success
            else SendResultEvent.Failure(mapErrorMessage(resultCode))
        )
    }

    private fun mapErrorMessage(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "无服务，发送失败"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "射频已关闭"
        SmsManager.RESULT_ERROR_NULL_PDU -> "短信内容异常"
        else -> "发送失败"
    }

    private fun refreshMessages() {
        _messages.clear()
        offset = 0
        isLoadingMore = false
        hasMore = true
        loadVersion++
        fetchMessages()
    }

    override fun onCleared() {
        runCatching { context.unregisterReceiver(spamDetectionReceiver) }
        super.onCleared()
    }

    companion object {
        private const val ACTION_SMS_SENT = "vip.mystery0.pixel.text.action.SMS_SENT"
        private val sendRequestCounter = AtomicInteger(0)
    }
}
