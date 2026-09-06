package vip.mystery0.pixel.text.data.repository.mirror

import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.mirror.*
import vip.mystery0.pixel.text.data.source.mirror.MirrorAttachmentStore
import vip.mystery0.pixel.text.data.source.mirror.ProviderRowSnapshot
import java.io.FileNotFoundException

/** 所有结构提交、删除、文件绑定与回收使用同一进程内锁。 */
internal object MirrorSynchronizationLock { val mutex = Mutex() }

class MirrorAttachmentCopier(
    private val database: MessageMirrorDatabase,
    private val store: MirrorAttachmentStore,
) {
    private val dao get() = database.mirrorDao()

    suspend fun copyPart(localId: Long, revision: Long, partId: Long) = withContext(Dispatchers.IO) {
        val jobKey = "$localId:$revision:$partId"
        val prepared = MirrorSynchronizationLock.mutex.withLock {
            if (jobKey in activeCopies) return@withLock null
            val record = dao.getLocal(localId) ?: return@withLock null
            val attachment = dao.attachment(localId, partId) ?: return@withLock null
            if (record.message.revision != revision || attachment.revision != revision ||
                !record.message.structureComplete || attachment.state == "PENDING_DOWNLOAD") return@withLock null
            if (attachment.state == "READY" && attachment.verifiedAt != null &&
                System.currentTimeMillis() - attachment.verifiedAt < VERIFICATION_INTERVAL) return@withLock null
            val part = record.parts.firstOrNull { it.sourceId == partId } ?: return@withLock null
            if (!dao.updateAttachmentIfCurrent(attachment.copy(state = "COPYING", error = null))) return@withLock null
            val stem = store.reserveCopy(localId, revision)
            activeCopies += jobKey
            Triple(attachment, part, stem)
        } ?: return@withContext
        val (attachment, part, stem) = prepared
        try {
            // 大文件流复制不持有结构锁，元数据更新和删除可以继续。
            val copied = store.copy(localId, revision, partId, stem)
            MirrorSynchronizationLock.mutex.withLock {
                val sameContent = attachment.sha256 == copied.sha256 && attachment.byteCount == copied.byteCount &&
                    attachment.relativePath?.let { store.exists(it) } == true
                val ready = attachment.copy(
                    relativePath = if (sameContent) attachment.relativePath else copied.relativePath,
                    byteCount = copied.byteCount, sha256 = copied.sha256, state = "READY", error = null,
                    attempts = 0, retryAfter = 0, verifiedAt = System.currentTimeMillis(),
                )
                if (!dao.updateAttachmentIfCurrent(ready) || sameContent) {
                    dao.enqueueCleanup(MirrorFileCleanupEntity(copied.relativePath))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            MirrorSynchronizationLock.mutex.withLock {
                val snapshot = ProviderRowSnapshot.decode(part.rawSnapshot)
                val inlineOnly = error is FileNotFoundException && part.text != null &&
                    snapshot.string("_data").isNullOrEmpty() &&
                    (part.mimeType?.startsWith("text/") == true || part.mimeType == "application/smil")
                if (inlineOnly) {
                    // Provider 未暴露流时，文本本体已由可逆源快照完整持久化。
                    dao.updateAttachmentIfCurrent(attachment.copy(state = "READY", relativePath = null,
                        byteCount = null, sha256 = null, error = null, attempts = 0,
                        verifiedAt = System.currentTimeMillis()))
                } else {
                    val category = when {
                        error is SecurityException -> "permission_denied"
                        error is FileNotFoundException -> "source_unreadable"
                        generateSequence<Throwable>(error) { it.cause }.any { it.message?.contains("ENOSPC") == true } -> "no_space"
                        else -> "copy_failed"
                    }
                    val attempts = attachment.attempts + 1
                    val delay = minOf(6 * 60 * 60_000L, 30_000L * (1L shl attempts.coerceAtMost(10)))
                    dao.updateAttachmentIfCurrent(attachment.copy(
                        state = if (category in setOf("permission_denied", "source_unreadable")) "SOURCE_UNREADABLE" else "COPY_FAILED",
                        error = category, attempts = attempts, retryAfter = System.currentTimeMillis() + delay,
                    ))
                    Log.w("MirrorAttachmentCopier", "attachment copy failed category=$category")
                }
            }
        } finally {
            // 取消也释放活跃保留；未绑定文件入队，READY 的引用在回收时再次确认。
            withContext(NonCancellable) {
                MirrorSynchronizationLock.mutex.withLock {
                    store.releaseCopy(stem)
                    activeCopies -= jobKey
                    listOf("$stem.partial", "$stem.blob").forEach { path ->
                        if (dao.referenceCount(path) == 0) dao.enqueueCleanup(MirrorFileCleanupEntity(path))
                    }
                }
            }
        }
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        MirrorSynchronizationLock.mutex.withLock {
            val now = System.currentTimeMillis()
            dao.allAttachments().forEach { attachment ->
                currentCoroutineContext().ensureActive()
                if ("${attachment.localId}:${attachment.revision}:${attachment.partId}" in activeCopies) return@forEach
                // 已进入失败退避的记录不因同一个缺失文件反复清空 retryAfter。
                val missing = attachment.state == "READY" && attachment.relativePath?.let { !store.exists(it) } == true
                val needsVerification = attachment.state == "READY" &&
                    now - (attachment.verifiedAt ?: 0) >= VERIFICATION_INTERVAL
                if (attachment.state == "COPYING" || missing || needsVerification) {
                    dao.updateAttachmentIfCurrent(attachment.copy(state = "SOURCE_PRESENT", retryAfter = 0,
                        error = if (missing) "local_file_missing" else null))
                }
            }
            store.files().forEach { path ->
                currentCoroutineContext().ensureActive()
                if (!store.isActive(path) && dao.referenceCount(path) == 0) dao.enqueueCleanup(MirrorFileCleanupEntity(path))
            }
        }
        drainCleanupQueue()
    }

    suspend fun drainCleanupQueue(timeBudgetMillis: Long = 10_000): Boolean = withContext(Dispatchers.IO) {
        MirrorSynchronizationLock.mutex.withLock {
            val deadline = SystemClock.elapsedRealtime() + timeBudgetMillis.coerceAtLeast(1)
            var afterPath = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                val batch = dao.cleanupTasks(afterPath)
                if (batch.isEmpty()) break
                // 使用路径键集翻页，本次失败任务不阻塞后续文件，也不在本轮忙重试。
                for (task in batch) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    if (store.isActive(task.relativePath)) continue
                    if (dao.referenceCount(task.relativePath) > 0) {
                        dao.removeCleanup(task.relativePath)
                    } else {
                        val deleted = runCatching { store.delete(task.relativePath) }.getOrDefault(false)
                        if (deleted) dao.removeCleanup(task.relativePath) else dao.failedCleanup(task.relativePath)
                    }
                }
                afterPath = batch.last().relativePath
            }
            dao.cleanupCount() > 0
        }
    }

    companion object {
        private const val VERIFICATION_INTERVAL = 24 * 60 * 60_000L
        // 仅在 MirrorSynchronizationLock 内访问，进程死亡后由持久 COPYING 状态恢复。
        private val activeCopies = mutableSetOf<String>()
    }
}
