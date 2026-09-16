package vip.mystery0.pixel.text.data.repository.mirror

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.MirrorDirtyEntity
import vip.mystery0.pixel.text.data.db.mirror.MirrorSyncStateEntity
import vip.mystery0.pixel.text.data.source.mirror.SourceRead
import vip.mystery0.pixel.text.data.source.mirror.TelephonyMirrorSource
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import java.util.UUID

/**
 * Fast-path synchronizer for user-visible refreshes and ordinary provider change events.
 *
 * A completed full reconciliation leaves a contiguous checkpoint for each transport. This
 * synchronizer only reads rows after that checkpoint, then refreshes targeted dirty messages.
 * It deliberately does not perform generation-based deletion detection; the daily/full
 * reconciliation remains responsible for integrity checking and missed generic mutations.
 */
class MessageMirrorIncrementalSynchronizer(
    private val database: MessageMirrorDatabase,
    private val source: TelephonyMirrorSource,
) {
    private val dao get() = database.mirrorDao()
    private val sync get() = database.syncDao()

    data class Result(
        val remaining: Boolean,
        val requiresFullReconcile: Boolean,
    )

    /** Ordinary provider notifications use this as a wake signal without requesting a full scan. */
    suspend fun markWake() {
        sync.markDirty(
            MirrorDirtyEntity(
                key = INCREMENTAL_WAKE_KEY,
                transport = null,
                sourceId = null,
                token = UUID.randomUUID().toString(),
            )
        )
    }

    suspend fun syncRecent(timeBudgetMillis: Long = 10_000): Result = withContext(Dispatchers.IO) {
        val baseReady = listOf("ROUND", MessageTransport.SMS.name, MessageTransport.MMS.name)
            .all { sync.state(it)?.complete == true }
        if (!baseReady) {
            return@withContext Result(remaining = true, requiresFullReconcile = true)
        }

        val deadline = SystemClock.elapsedRealtime() + timeBudgetMillis.coerceAtLeast(1)
        var result = Result(remaining = false, requiresFullReconcile = false)
        try {
            MirrorSynchronizationLock.mutex.withLock {
                var appendedAll = true
                for (transport in MessageTransport.entries) {
                    if (SystemClock.elapsedRealtime() >= deadline || !appendNewMessages(transport, deadline)) {
                        appendedAll = false
                        break
                    }
                }

                val dirtyResult = refreshTargetedDirty(deadline)
                if (appendedAll) {
                    sync.dirty(INCREMENTAL_WAKE_KEY)?.let { sync.acknowledge(it.key, it.token) }
                }
                result = Result(
                    remaining = !appendedAll || sync.dirtyCount() > 0,
                    requiresFullReconcile = dirtyResult.requiresFullReconcile,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "incremental sync failed category=${error.javaClass.simpleName}")
            result = Result(remaining = true, requiresFullReconcile = false)
        }
        result
    }

    private suspend fun appendNewMessages(transport: MessageTransport, deadline: Long): Boolean {
        var state = sync.state(transport.name) ?: return false
        if (!state.complete) return false
        var checkpoint = state.checkpoint

        while (true) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline) return false
            val page = if (transport == MessageTransport.SMS) {
                source.readSmsPage(checkpoint)
            } else {
                source.readMmsPage(checkpoint)
            }
            when (page) {
                is SourceRead.Failure -> return false
                is SourceRead.Success -> {
                    var columns = parseColumns(state.columns) + page.columns
                    if (page.value.isEmpty()) return true

                    val structures = page.value.map { source.toStructure(transport, it, state.generation) }
                    columns = columns + structures.flatMap { it.sourceColumns }
                    var previousId = checkpoint
                    structures.forEach { structure ->
                        check(previousId == null || structure.message.sourceId > requireNotNull(previousId)) {
                            "unordered_source_page"
                        }
                        previousId = structure.message.sourceId
                    }
                    val nextCheckpoint = structures.last().message.sourceId
                    check(checkpoint == null || nextCheckpoint > requireNotNull(checkpoint)) {
                        "non_advancing_page"
                    }
                    state = state.copy(
                        checkpoint = nextCheckpoint,
                        processedCount = state.processedCount + structures.size,
                        columns = JSONArray(columns.sorted()).toString(),
                        complete = true,
                        running = false,
                        error = null,
                        lastSuccessTime = System.currentTimeMillis(),
                    )
                    dao.commitBatch(structures, state)
                    structures.filter { !it.childrenComplete }.forEach { structure ->
                        markDirty(SourceMessageKey(transport, structure.message.sourceId))
                    }
                    checkpoint = nextCheckpoint
                    Log.d(TAG, "append batch collection=${transport.name} count=${structures.size} checkpoint=$checkpoint")
                }
            }
        }
    }

    private suspend fun refreshTargetedDirty(deadline: Long): DirtyResult {
        var requiresFullReconcile = false
        repeat(MAX_DIRTY_BATCHES) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                return DirtyResult(requiresFullReconcile)
            }
            val batch = sync.dirtyBatch().filter { it.transport != null }
            if (batch.isEmpty()) return DirtyResult(requiresFullReconcile)

            var progressed = false
            for (dirty in batch) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) {
                    return DirtyResult(requiresFullReconcile)
                }
                val key = SourceMessageKey(
                    MessageTransport.valueOf(requireNotNull(dirty.transport)),
                    requireNotNull(dirty.sourceId),
                )
                when (val sourceResult = source.readMessage(key)) {
                    is SourceRead.Failure -> Unit
                    is SourceRead.Success -> {
                        val present = sourceResult.value
                        if (present == null) {
                            // Deletion needs the full synchronizer so its deletion callbacks and
                            // generation safety checks remain authoritative.
                            requiresFullReconcile = true
                            continue
                        }
                        val generation = sync.state(key.transport.name)?.generation ?: 0
                        dao.replaceStructure(
                            present.copy(message = present.message.copy(generation = generation))
                        )
                        if (!present.childrenComplete) continue
                        if (dirty.key.startsWith("attachments:") && key.transport == MessageTransport.MMS) {
                            dao.invalidateAttachmentContent(key.sourceId)
                        }
                        sync.acknowledge(dirty.key, dirty.token)
                        val previous = sync.state("TARGETED") ?: MirrorSyncStateEntity("TARGETED")
                        sync.putState(
                            previous.copy(
                                complete = true,
                                lastSuccessTime = System.currentTimeMillis(),
                                processedCount = previous.processedCount + 1,
                                error = null,
                            )
                        )
                        progressed = true
                    }
                }
            }
            if (!progressed) return DirtyResult(requiresFullReconcile)
        }
        return DirtyResult(requiresFullReconcile)
    }

    private suspend fun markDirty(key: SourceMessageKey) {
        sync.markDirty(
            MirrorDirtyEntity(
                key = "${key.transport}:${key.sourceId}",
                transport = key.transport.name,
                sourceId = key.sourceId,
                token = UUID.randomUUID().toString(),
            )
        )
    }

    private fun parseColumns(value: String): Set<String> {
        val array = JSONArray(value)
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private data class DirtyResult(val requiresFullReconcile: Boolean)

    private companion object {
        const val TAG = "MessageMirrorFastSync"
        const val INCREMENTAL_WAKE_KEY = "incremental"
        const val MAX_DIRTY_BATCHES = 10
    }
}
