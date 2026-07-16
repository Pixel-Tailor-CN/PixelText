package vip.mystery0.pixel.text.viewmodel

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.repository.UnreadSmsCounter
import vip.mystery0.pixel.text.domain.model.UnreadSmsCountFilter
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

class UnreadBadgeViewModel(
    context: Context,
    private val unreadSmsCounter: UnreadSmsCounter,
    private val settingsRepository: AppSettingsRepository,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    init {
        viewModelScope.launch {
            merge(
                observeSmsChanges(),
                unreadSmsCounter.observeClassificationChanges(),
                settingsRepository.settings.map { Unit },
            ).onStart { emit(Unit) }.collect {
                refreshCount()
            }
        }
    }

    private suspend fun refreshCount() {
        val settings = settingsRepository.settings.value
        try {
            val count = withContext(Dispatchers.IO) {
                unreadSmsCounter.count(
                    UnreadSmsCountFilter(
                        includeNormalMessages = true,
                        includeSpamMessages = true,
                        includeArchivedMessages = false,
                        excludeFullySpamConversations =
                            settings.hideFullySpamConversationsEnabled,
                    )
                )
            }
            _unreadCount.value = count.coerceAtLeast(0)
        } catch (error: Exception) {
            Log.e(
                TAG,
                "unread badge count failed error=${error::class.java.simpleName}",
                error,
            )
        }
    }

    private fun observeSmsChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        appContext.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            observer,
        )
        awaitClose { appContext.contentResolver.unregisterContentObserver(observer) }
    }

    private companion object {
        const val TAG = "UnreadBadgeViewModel"
    }
}
