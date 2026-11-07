package vip.mystery0.pixel.text.worker

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

internal data class SpamScanProgress(
    val processed: Int,
    val total: Int,
    val spamCount: Int
)

internal class SpamScanProgressReporter(
    scope: CoroutineScope,
    publish: suspend (SpamScanProgress) -> Unit
) {
    private data class Snapshot(
        val progress: SpamScanProgress? = null,
        val closed: Boolean = false
    )

    private val latest = AtomicReference(Snapshot())
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val reporting = scope.async(Dispatchers.IO) {
        var lastAttempted: SpamScanProgress? = null

        suspend fun publishLatest() {
            val progress = latest.get().progress ?: return
            if (progress == lastAttempted) return
            // 相同快照只尝试一次；失败不阻断扫描，后续变化仍可继续发布。
            lastAttempted = progress
            try {
                publish(progress)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("SpamScanProgressReporter", "spam scan progress publish failed", error)
            }
        }

        while (true) {
            val signal = withTimeoutOrNull(1_000L) { signals.receiveCatching() }
            publishLatest()
            if (signal?.isClosed == true) break
        }
    }

    fun update(progress: SpamScanProgress) {
        latest.updateAndGet { snapshot ->
            if (snapshot.closed) snapshot else snapshot.copy(progress = progress)
        }
    }

    fun batchCompleted(progress: SpamScanProgress) {
        update(progress)
        signals.trySend(Unit)
    }

    suspend fun close() {
        // 原子冻结快照，避免与关闭并发的更新遗留在上报协程结束之后。
        latest.updateAndGet { it.copy(closed = true) }
        signals.close()
        reporting.await()
    }
}
