package vip.mystery0.pixel.text.data.repository.mirror

import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import vip.mystery0.pixel.text.data.db.mirror.*
import vip.mystery0.pixel.text.data.source.mirror.*
import vip.mystery0.pixel.text.domain.model.mirror.*
import java.util.UUID

class MessageMirrorSynchronizer(
    private val database: MessageMirrorDatabase,
    private val source: TelephonyMirrorSource,
    private val copier: MirrorAttachmentCopier,
) {
    private val dao get() = database.mirrorDao()
    private val sync get() = database.syncDao()
    var onMessageDeleted: (suspend (SourceMessageKey) -> Unit)? = null
    /** 镜像删除事务成功后同步失效，清除删除前回调挂起期间的新派生缓存。 */
    var onMessageDeletionCommitted: ((SourceMessageKey) -> Unit)? = null

    /** 不等待扫描锁，确保扫描中的 Provider 事件可以立即持久化。 */
    suspend fun markDirty(key: SourceMessageKey?, verifyAttachments: Boolean = false) {
        sync.markDirty(MirrorDirtyEntity(
            key = key?.let { (if (verifyAttachments) "attachments:" else "") + "${it.transport}:${it.sourceId}" } ?: "collection",
            transport = key?.transport?.name, sourceId = key?.sourceId, token = UUID.randomUUID().toString(),
        ))
    }

    /** 用户明确刷新时请求全文校验；普通前台恢复仍使用定期校验策略。 */
    suspend fun requestAttachmentVerification() {
        var afterId = 0L
        while (true) {
            val batch = dao.mmsVerificationBatch(afterId)
            if (batch.isEmpty()) return
            batch.forEach { markDirty(SourceMessageKey(MessageTransport.MMS, it.sourceId), verifyAttachments = true) }
            afterId = batch.last().localId
        }
    }

    suspend fun reconcile(timeBudgetMillis: Long = 120_000): Boolean = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + timeBudgetMillis.coerceAtLeast(1)
        var remaining = false
        MirrorSynchronizationLock.mutex.withLock {
            val previousRound = sync.state("ROUND")
            var round = if (previousRound != null && !previousRound.complete) previousRound
                else MirrorSyncStateEntity("ROUND", generation = (previousRound?.generation ?: 0) + 1,
                    dirtyToken = sync.dirty("collection")?.token)
            sync.putState(round)
            val completedSources = parseColumns(round.completedSources).toMutableSet()
            // 本轮成功集合单独持久化；预算续跑不能重新扫描已完成的源而饿死另一集合。
            for (transport in MessageTransport.entries) {
                if (transport.name in completedSources) continue
                if (SystemClock.elapsedRealtime() >= deadline) break
                if (scan(transport, deadline)) {
                    completedSources += transport.name
                    round = round.copy(completedSources = JSONArray(completedSources.sorted()).toString())
                    sync.putState(round)
                }
            }
            val smsComplete = "SMS" in completedSources
            val mmsComplete = "MMS" in completedSources
            if (smsComplete && mmsComplete && SystemClock.elapsedRealtime() < deadline) {
                readAncillarySources()
                round = round.copy(complete = true, lastSuccessTime = System.currentTimeMillis())
                sync.putState(round)
                round.dirtyToken?.let { sync.acknowledge("collection", it) }
            }
            // 有限批次防止高频写入让一次 Worker 永不结束；剩余任务持久保留。
            repeat(10) {
                if (SystemClock.elapsedRealtime() >= deadline) return@repeat
                val batch = sync.dirtyBatch().filter { it.transport != null }
                if (batch.isEmpty()) return@repeat
                for (dirty in batch) {
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    currentCoroutineContext().ensureActive()
                    val key = SourceMessageKey(MessageTransport.valueOf(requireNotNull(dirty.transport)), requireNotNull(dirty.sourceId))
                    if (refreshOne(key)) {
                        if (dirty.key.startsWith("attachments:") && key.transport == MessageTransport.MMS) {
                            dao.invalidateAttachmentContent(key.sourceId)
                        }
                        sync.acknowledge(dirty.key, dirty.token)
                    }
                }
            }
            remaining = !round.complete || sync.dirtyCount() > 0
            Log.i("MessageMirrorSync", "reconcile finished sms_complete=$smsComplete mms_complete=$mmsComplete dirty_count=${sync.dirtyCount()}")
        }
        val cleanupRemaining = copier.drainCleanupQueue()
        remaining || cleanupRemaining
    }

    /** 附件任务与基础元数据扫描分开调度，不触发网络下载。 */
    suspend fun copyPendingAttachments(timeBudgetMillis: Long = 120_000): Boolean = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + timeBudgetMillis.coerceAtLeast(1)
        copier.recover()
        // 避免单次任务被超大库长期占用；持久状态让后续 Worker 接续。
        repeat(10) {
            val pending = dao.pendingAttachments(System.currentTimeMillis())
            if (pending.isEmpty()) {
                val cleanupRemaining = copier.drainCleanupQueue()
                return@withContext dao.unfinishedAttachmentCount() > 0 || cleanupRemaining
            }
            var attempted = false
            pending.forEach { attachment ->
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) return@withContext true
                if (dao.getLocal(attachment.localId)?.message?.structureComplete == true) {
                    attempted = true
                    copier.copyPart(attachment.localId, attachment.revision, attachment.partId)
                }
            }
            if (!attempted) return@withContext true
        }
        val cleanupRemaining = copier.drainCleanupQueue()
        dao.unfinishedAttachmentCount() > 0 || cleanupRemaining
    }

    private suspend fun scan(transport: MessageTransport, deadline: Long): Boolean {
        if (SystemClock.elapsedRealtime() >= deadline) return false
        val previous = sync.state(transport.name)
        // 主动预算让出与崩溃中断区分；只续接已提交的完整批次。
        if (previous?.error == "budget_yield" && !previous.running) {
            return scanPass(transport, previous, canDelete = true, deadline = deadline)
        }
        // 先续完中断导入以保留进度，再开启独立完整轮次；续扫绝不用于删除。
        if (previous != null && !previous.complete && previous.checkpoint != null) {
            if (!scanPass(transport, previous, canDelete = false, deadline = deadline)) return false
        }
        val current = sync.state(transport.name)
        val fresh = MirrorSyncStateEntity(transport.name, generation = (current?.generation ?: 0) + 1,
            running = true, lastSuccessTime = current?.lastSuccessTime)
        return scanPass(transport, fresh, canDelete = true, deadline = deadline)
    }

    private suspend fun scanPass(transport: MessageTransport, initial: MirrorSyncStateEntity, canDelete: Boolean, deadline: Long): Boolean {
        var state = initial.copy(complete = false, running = true, error = null)
        sync.putState(state)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) {
                    sync.putState(state.copy(running = false, error = if (canDelete) "budget_yield" else "interrupted_import"))
                    return false
                }
                val page = if (transport == MessageTransport.SMS) source.readSmsPage(state.checkpoint) else source.readMmsPage(state.checkpoint)
                when (page) {
                    is SourceRead.Failure -> {
                        sync.putState(state.copy(running = false, error = page.category))
                        return false
                    }
                    is SourceRead.Success -> {
                        var columns = parseColumns(state.columns) + page.columns
                        if (page.value.isEmpty()) {
                            state = state.copy(columns = JSONArray(columns.sorted()).toString())
                            break
                        }
                        val structures = page.value.map { source.toStructure(transport, it, state.generation) }
                        columns = columns + structures.flatMap { it.sourceColumns }
                        var previousId = state.checkpoint
                        structures.forEach {
                            check(previousId == null || it.message.sourceId > requireNotNull(previousId)) { "unordered_source_page" }
                            previousId = it.message.sourceId
                        }
                        val checkpoint = structures.last().message.sourceId
                        check(state.checkpoint == null || checkpoint > requireNotNull(state.checkpoint)) { "non_advancing_page" }
                        state = state.copy(checkpoint = checkpoint, processedCount = state.processedCount + structures.size,
                            columns = JSONArray(columns.sorted()).toString())
                        dao.commitBatch(structures, state)
                        structures.filter { !it.childrenComplete }.forEach {
                            markDirty(SourceMessageKey(transport, it.message.sourceId))
                        }
                        Log.d("MessageMirrorSync", "scan batch collection=${transport.name} count=${structures.size} parts=${structures.sumOf { it.parts.size }} attachments=${structures.sumOf { it.attachments.size }} incomplete=${structures.count { !it.childrenComplete }} columns=${columns.size}")
                    }
                }
            }
            if (canDelete && !confirmMissing(transport, state.generation, deadline)) {
                sync.putState(state.copy(running = false, error = "budget_yield"))
                return false
            }
            sync.putState(state.copy(complete = true, running = false, error = null, lastSuccessTime = System.currentTimeMillis()))
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val category = error.javaClass.simpleName
            sync.putState(state.copy(complete = false, running = false, error = category))
            Log.w("MessageMirrorSync", "scan failed collection=${transport.name} category=$category")
            return false
        }
    }

    private suspend fun confirmMissing(transport: MessageTransport, generation: Long, deadline: Long): Boolean {
        var afterLocalId = 0L
        while (true) {
            val candidates = dao.missingCandidates(transport.name, generation, afterLocalId)
            if (candidates.isEmpty()) break
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) return false
                val key = SourceMessageKey(transport, candidate.sourceId)
                // URI 精确存在性复查没有箱、thread、时间或业务分类筛选。
                when (val result = source.readMessage(key)) {
                    is SourceRead.Failure -> markDirty(key)
                    is SourceRead.Success -> {
                        val present = result.value
                        if (present != null) dao.replaceStructure(present.copy(message = present.message.copy(generation = generation)))
                        else deleteConfirmed(candidate, key)
                    }
                }
            }
            afterLocalId = candidates.last().localId
        }
        return true
    }

    private suspend fun deleteConfirmed(record: MirrorMessageEntity, key: SourceMessageKey): Boolean {
        // 派生缓存清理失败时保留源记录与 dirty，下一轮重试。
        try {
            onMessageDeleted?.invoke(key)
            currentCoroutineContext().ensureActive()
            // 将已确认的本地删除与同步失效作为短临界段完成，避免事务提交后因取消漏掉失效。
            return withContext(NonCancellable) {
                dao.deleteConfirmed(record.localId, record.revision).also { deleted ->
                    if (deleted) onMessageDeletionCommitted?.invoke(key)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            markDirty(key)
            return false
        }
    }

    private suspend fun refreshOne(key: SourceMessageKey): Boolean {
        return when (val result = source.readMessage(key)) {
            is SourceRead.Failure -> {
                val previous = sync.state("TARGETED") ?: MirrorSyncStateEntity("TARGETED")
                sync.putState(previous.copy(complete = false, error = result.category))
                false
            }
            is SourceRead.Success -> {
                val present = result.value
                if (present == null) {
                    val old = dao.get(key.transport.name, key.sourceId)?.message
                    if (old != null && !deleteConfirmed(old, key)) return false
                } else {
                    val generation = sync.state(key.transport.name)?.generation ?: 0
                    dao.replaceStructure(present.copy(message = present.message.copy(generation = generation)))
                    if (!present.childrenComplete) return false
                }
                val previous = sync.state("TARGETED") ?: MirrorSyncStateEntity("TARGETED")
                sync.putState(previous.copy(complete = true, lastSuccessTime = System.currentTimeMillis(),
                    processedCount = previous.processedCount + 1, error = null))
                true
            }
        }
    }

    private suspend fun readAncillarySources() {
        val sources = source.readThreadSources()
        when (val threads = sources.threads) {
            is SourceRead.Failure -> sync.putState((sync.state("THREADS") ?: MirrorSyncStateEntity("THREADS")).copy(complete = false, error = threads.category))
            is SourceRead.Success -> {
                if (threads.value.any { it.long("_id") == null }) {
                    sync.putState((sync.state("THREADS") ?: MirrorSyncStateEntity("THREADS")).copy(complete = false, error = "missing_source_id"))
                } else {
                    val rows = threads.value.map { MirrorThreadSourceEntity(requireNotNull(it.long("_id")), it.encode()) }
                    sync.replaceThreads(rows, MirrorSyncStateEntity("THREADS", complete = true,
                        lastSuccessTime = System.currentTimeMillis(), columns = JSONArray(threads.columns.sorted()).toString(), processedCount = rows.size.toLong()))
                }
            }
        }
        when (val canonical = sources.canonicalAddresses) {
            is SourceRead.Failure -> sync.putState((sync.state("CANONICAL") ?: MirrorSyncStateEntity("CANONICAL")).copy(complete = false, error = canonical.category))
            is SourceRead.Success -> {
                if (canonical.value.any { it.long("_id") == null }) {
                    sync.putState((sync.state("CANONICAL") ?: MirrorSyncStateEntity("CANONICAL")).copy(complete = false, error = "missing_source_id"))
                } else {
                    val rows = canonical.value.map { MirrorCanonicalAddressEntity(requireNotNull(it.long("_id")), it.string("address"), it.encode()) }
                    sync.replaceCanonical(rows, MirrorSyncStateEntity("CANONICAL", complete = true,
                        lastSuccessTime = System.currentTimeMillis(), columns = JSONArray(canonical.columns.sorted()).toString(), processedCount = rows.size.toLong()))
                }
            }
        }
    }

    private fun parseColumns(value: String): Set<String> {
        val array = JSONArray(value)
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }
}
