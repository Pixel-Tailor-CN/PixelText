package vip.mystery0.pixel.text.data.repository.mirror

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.source.SmsAccessState
import vip.mystery0.pixel.text.data.source.smsAccessState
import vip.mystery0.pixel.text.data.source.mirror.SourceRead
import vip.mystery0.pixel.text.data.source.mirror.TelephonyMirrorSource
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey

class MirrorChangeObserver(
    context: Context,
    private val synchronizer: MessageMirrorSynchronizer,
    private val incrementalSynchronizer: MessageMirrorIncrementalSynchronizer,
    private val source: TelephonyMirrorSource,
    private val restoreSync: RestoreSyncCoordinator,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    @Volatile private var started = false
    private var callbacks: Job = SupervisorJob(scope.coroutineContext[Job])
    var onDirty: (() -> Unit)? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // 主线程只做状态判断；角色与权限的跨进程核对放到后台协程。
            val callbackJob = synchronized(this@MirrorChangeObserver) {
                if (!started) return
                callbacks
            }
            scope.launch(callbackJob) {
                try {
                    if (appContext.smsAccessState() != SmsAccessState.READY) {
                        stop()
                        return@launch
                    }
                    // 恢复会连续写入大量短信，统一由恢复结束后的单次对账处理。
                    if (restoreSync.isRestorePaused()) return@launch
                    val key = resolveKey(uri)
                    if (key == null) incrementalSynchronizer.markWake()
                    else synchronizer.markDirty(
                        key,
                        verifyAttachments = key.transport == MessageTransport.MMS &&
                            uri?.pathSegments?.contains("part") == true,
                    )
                    onDirty?.invoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    stop()
                } catch (error: Exception) {
                    Log.w("MirrorChangeObserver", "mirror callback failed category=${error.javaClass.simpleName}")
                }
            }
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (appContext.smsAccessState() != SmsAccessState.READY) {
            stop()
            return false
        }
        if (started) return false
        callbacks = SupervisorJob(scope.coroutineContext[Job])
        return try {
            listOf("sms", "mms", "mms-sms").forEach {
                resolver.registerContentObserver("content://$it".toUri(), true, observer)
            }
            started = true
            Log.i("MirrorChangeObserver", "mirror observation started")
            true
        } catch (_: SecurityException) {
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        callbacks.cancel()
        // 注册到一半时撤权，也清理已成功注册的 URI。
        runCatching { resolver.unregisterContentObserver(observer) }
        if (started) Log.i("MirrorChangeObserver", "mirror observation paused")
        started = false
    }

    private suspend fun resolveKey(uri: Uri?): SourceMessageKey? {
        if (uri == null) return null
        val path = uri.pathSegments
        return when (uri.authority) {
            "sms" -> path.singleOrNull()?.toLongOrNull()?.let { SourceMessageKey(MessageTransport.SMS, it) }
            "mms" -> when {
                path.size == 1 -> path[0].toLongOrNull()?.let { SourceMessageKey(MessageTransport.MMS, it) }
                path.size >= 2 && path[0].toLongOrNull() != null && path[1] in setOf("part", "addr") ->
                    SourceMessageKey(MessageTransport.MMS, requireNotNull(path[0].toLongOrNull()))
                path.size == 2 && path[0] == "part" -> path[1].toLongOrNull()?.let { id ->
                    (source.messageKeyForPart(id) as? SourceRead.Success)?.value
                }
                else -> null
            }
            else -> null
        }
    }
}
