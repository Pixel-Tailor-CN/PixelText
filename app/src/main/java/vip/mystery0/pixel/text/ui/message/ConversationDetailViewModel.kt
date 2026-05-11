package vip.mystery0.pixel.text.ui.message

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository

class ConversationDetailViewModel(
    private val repository: MessageRepository,
    private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
    val uiState: StateFlow<MessageUiState> = _uiState.asStateFlow()

    private val _address = MutableStateFlow<String>("")
    val address: StateFlow<String> = _address.asStateFlow()

    private var currentThreadId: Long = -1L
    private var offset = 0
    private var isLoadingMore = false
    private var hasMore = true
    private val _messages = mutableListOf<MessageModel>()

    fun loadThread(threadId: Long, address: String) {
        _address.value = address
        if (currentThreadId == threadId && _messages.isNotEmpty()) return

        currentThreadId = threadId
        _messages.clear()
        offset = 0
        hasMore = true

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
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        fetchMessages()
    }

    private fun fetchMessages() {
        viewModelScope.launch {
            repository.getMessagesByThread(currentThreadId, 20, offset)
                .catch { e ->
                    if (_messages.isEmpty()) {
                        _uiState.value = MessageUiState.Error(e.message ?: "Unknown error")
                    }
                    isLoadingMore = false
                }
                .collect { newMessages ->
                    if (newMessages.isEmpty()) {
                        hasMore = false
                    } else {
                        _messages.addAll(newMessages)
                        offset += newMessages.size
                        _uiState.value = MessageUiState.Success(_messages.toList())
                    }
                    isLoadingMore = false
                }
        }
    }

    fun sendMessage(address: String, message: String) {
        viewModelScope.launch {
            try {
                val smsManager = context.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(address, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(address, null, message, null, null)
                }

                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, message)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                    if (currentThreadId != -1L) {
                        put(Telephony.Sms.THREAD_ID, currentThreadId)
                    }
                }
                context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)

                kotlinx.coroutines.delay(500)

                // 如果是新会话，需要查询新的 threadId
                if (currentThreadId == -1L) {
                    context.contentResolver.query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms.THREAD_ID),
                        "${Telephony.Sms.ADDRESS} = ?",
                        arrayOf(address),
                        "${Telephony.Sms.DATE} DESC LIMIT 1"
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            currentThreadId = cursor.getLong(0)
                        }
                    }
                }

                _messages.clear()
                offset = 0
                hasMore = true
                fetchMessages()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}
