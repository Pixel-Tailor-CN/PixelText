package vip.mystery0.pixel.text.data.repository.mirror

import androidx.core.net.toUri

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.source.mirror.SourceRead
import vip.mystery0.pixel.text.data.source.mirror.TelephonyMirrorSource
import vip.mystery0.pixel.text.domain.model.mirror.*

class MirrorChangeObserver(
    context: Context,
    private val synchronizer: MessageMirrorSynchronizer,
    private val incrementalSynchronizer: MessageMirrorIncrementalSynchronizer,
    private val scope: CoroutineScope,
) {
    private val resolver = context.applicationContext.contentResolver
    private val source = TelephonyMirrorSource(context)
    private var started = false
    /** 可由接入层安排唯一 WorkManager 任务；观察回调自身只持久化提示。 */
    var onDirty: (() -> Unit)? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scope.launch {
                val key = resolveKey(uri)
                if (key == null) {
                    incrementalSynchronizer.markWake()
                } else {
                    synchronizer.markDirty(
                        key,
                        verifyAttachments = key.transport == MessageTransport.MMS &&
                            uri?.pathSegments?.contains("part") == true,
                    )
                }
                onDirty?.invoke()
            }
        }
    }

    fun start() {
        if (started) return
        resolver.registerContentObserver("content://sms".toUri(), true, observer)
        resolver.registerContentObserver("content://mms".toUri(), true, observer)
        resolver.registerContentObserver("content://mms-sms".toUri(), true, observer)
        started = true

    }

    fun stop() {
        if (!started) return
        resolver.unregisterContentObserver(observer)
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
