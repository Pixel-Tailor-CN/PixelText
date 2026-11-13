package vip.mystery0.pixel.text.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.repository.UnreadSmsCounter
import vip.mystery0.pixel.text.data.source.SmsAccessState
import vip.mystery0.pixel.text.data.source.smsAccessState
import vip.mystery0.pixel.text.domain.model.UnreadSmsCountFilter
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class UnreadBadgeViewModel(
    context: Context,
    private val unreadSmsCounter: UnreadSmsCounter,
    private val settingsRepository: AppSettingsRepository,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()
    private val smsAccessReady = MutableStateFlow(appContext.smsAccessState() == SmsAccessState.READY)
    private val smsRoleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) {
                refreshAccessState()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            smsRoleReceiver,
            IntentFilter(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        viewModelScope.launch {
            merge(
                observeSmsChanges(),
                smsAccessReady.map { },
                unreadSmsCounter.observeClassificationChanges(),
                unreadSmsCounter.observeMirrorChanges(),
                settingsRepository.settings.map { },
            ).onStart { emit(Unit) }.conflate().collect {
                refreshCount()
            }
        }
    }

    fun refreshAccessState() {
        smsAccessReady.value = appContext.smsAccessState() == SmsAccessState.READY
    }

    override fun onCleared() {
        runCatching { appContext.unregisterReceiver(smsRoleReceiver) }
        super.onCleared()
    }

    private suspend fun refreshCount() {
        val settings = settingsRepository.settings.value
        try {
            val count = withContext(Dispatchers.IO) {
                unreadSmsCounter.count(
                    UnreadSmsCountFilter(
                        includeNormalMessages = true,
                        includeSpamMessages = !settings.spamIsolationEnabled,
                        includeArchivedMessages = false,
                        excludeFullySpamConversations = false,
                    )
                )
            }
            _unreadCount.value = count.coerceAtLeast(0)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(
                TAG,
                "unread badge count failed error=${error::class.java.simpleName}",
                error,
            )
        }
    }

    private fun observeSmsChanges(): Flow<Unit> = smsAccessReady.flatMapLatest { ready ->
        if (!ready) return@flatMapLatest emptyFlow()
        callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    trySend(Unit)
                }
            }
            val registered = try {
                appContext.contentResolver.registerContentObserver(
                    Telephony.Sms.CONTENT_URI,
                    true,
                    observer,
                )
                true
            } catch (_: SecurityException) {
                false
            }
            awaitClose {
                if (registered) {
                    runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "UnreadBadgeViewModel"
    }
}
