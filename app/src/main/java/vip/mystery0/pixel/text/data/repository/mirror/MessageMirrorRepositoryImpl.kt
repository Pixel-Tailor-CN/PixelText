package vip.mystery0.pixel.text.data.repository.mirror

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import vip.mystery0.pixel.text.data.db.mirror.*
import vip.mystery0.pixel.text.data.source.mirror.MirrorAttachmentStore
import vip.mystery0.pixel.text.data.source.mirror.ProviderRowSnapshot
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.mirror.*
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository

class MessageMirrorRepositoryImpl(
    private val database: MessageMirrorDatabase,
    private val store: MirrorAttachmentStore,
) : MessageMirrorRepository {
    private val dao get() = database.mirrorDao()

    override suspend fun getMessage(key: SourceMessageKey): MirrorMessageModel? =
        dao.get(key.transport.name, key.sourceId)?.toModel()

    override fun observeMessage(key: SourceMessageKey): Flow<MirrorMessageModel?> =
        dao.observeMessage(key.transport.name, key.sourceId).map { it?.toModel() }
            .distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun observeMessagesByThread(threadId: Long, limit: Int, offset: Int): Flow<List<MirrorMessageModel>> =
        dao.observeThread(threadId, limit.coerceAtLeast(1), offset.coerceAtLeast(0))
            .map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)

    override fun observeAllMessages(): Flow<List<MirrorMessageModel>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)

    override fun observeThreadChanges(threadId: Long): Flow<Unit> =
        dao.observeThreadChanges(threadId).map { }.flowOn(Dispatchers.IO)

    override fun observeConversationSummaries(): Flow<List<ConversationModel>> =
        dao.observeConversations().map { rows -> rows.map {
            ConversationModel(it.threadId, it.address.orEmpty(), it.snippet.orEmpty(), it.timestamp ?: 0,
                unreadCount = it.unreadCount, isMms = it.latestIsMms, hasMms = it.containsMms)
        } }.flowOn(Dispatchers.IO)

    override fun observeSyncState(): Flow<MirrorSyncState> {
        val counts = combine(dao.observeIncompleteCount(), dao.observePendingDownloads(),
            dao.observeAttachmentFailures(), dao.observePendingAttachments(), database.syncDao().observeDirtyCount()) {
                incomplete, downloads, failed, pending, dirty -> intArrayOf(incomplete, downloads, failed, pending, dirty)
        }
        return combine(database.syncDao().observeStates(), counts) { states, count ->
            val required = setOf("SMS", "MMS", "THREADS", "CANONICAL")
            val completed = states.filter { it.complete }.map { it.collection }.toSet()
            MirrorSyncState(
                phase = when {
                    states.isEmpty() -> MirrorInitializationPhase.NOT_STARTED
                    states.any { it.running } -> MirrorInitializationPhase.SCANNING
                    completed.containsAll(required) && states.firstOrNull { it.collection == "ROUND" }?.complete == true &&
                        count[0] == 0 && count[4] == 0 -> MirrorInitializationPhase.COMPLETE
                    else -> MirrorInitializationPhase.PARTIAL
                },
                completedCollections = completed, incompleteStructureCount = count[0], pendingDownloadCount = count[1],
                attachmentFailureCount = count[2], pendingAttachmentCount = count[3],
                lastSuccessTime = states.filter { it.collection in setOf("SMS", "MMS") }.let { roots ->
                    if (roots.size == 2 && roots.all { it.lastSuccessTime != null }) roots.minOf { requireNotNull(it.lastSuccessTime) } else null
                },
                collections = states.map { state ->
                    val columns = JSONArray(state.columns)
                    MirrorCollectionState(state.collection, state.generation, state.checkpoint, state.complete,
                        state.lastSuccessTime, state.error, (0 until columns.length()).map { columns.getString(it) }.toSet(), state.processedCount)
                },
            )
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    private fun MirrorMessageRecord.toModel(): MirrorMessageModel {
        val attachmentMap = attachments.associateBy { it.partId }
        return MirrorMessageModel(
            localId = message.localId, key = SourceMessageKey(MessageTransport.valueOf(message.transport), message.sourceId),
            revision = message.revision, threadId = message.threadId, timestamp = message.timestamp,
            originalDate = message.originalDate, dateUnit = message.dateUnit, subscriptionId = message.subscriptionId,
            boxType = message.boxType, read = message.read, seen = message.seen, body = sms?.body,
            address = sms?.address, subject = sms?.subject ?: mms?.subject, decodedSubject = mms?.decodedSubject,
            pduType = mms?.pduType, downloadStatus = mms?.downloadStatus, structureComplete = message.structureComplete,
            rawValues = ProviderRowSnapshot.decode(sms?.rawSnapshot ?: mms?.rawSnapshot ?: "[]").values,
            addresses = addresses.sortedBy { it.ordinal }.map {
                MirrorAddressModel(it.sourceId, it.type, it.charset, it.address, it.normalizedAddress,
                    ProviderRowSnapshot.decode(it.rawSnapshot).values)
            },
            parts = parts.sortedWith(compareBy<MirrorPartEntity> { it.sequence }.thenBy { it.sourceId }).map { part ->
                val attachment = attachmentMap[part.sourceId]
                MirrorPartModel(part.sourceId, part.sequence, part.mimeType, part.charset, part.name, part.filename,
                    part.contentId, part.contentLocation, part.text, ProviderRowSnapshot.decode(part.rawSnapshot).values,
                    attachment?.let { MirrorAttachmentModel(MirrorAttachmentState.valueOf(it.state),
                        if (it.state == "READY") it.relativePath?.let(store::localUri) else null,
                        it.sourceUri, it.byteCount, it.sha256, it.error) })
            },
        )
    }
}

/** 仅供现有页面过渡；完整地址和全部附件仍保留在 MirrorMessageModel 中。 */
fun MirrorMessageModel.toMessageModel(): MessageModel {
    val mms = key.transport == MessageTransport.MMS
    val preferredType = if (boxType == 1) 137 else 151
    val sender = address ?: addresses.firstOrNull { it.type == preferredType && it.address != "insert-address-token" }?.address
        ?: addresses.firstOrNull { it.address != "insert-address-token" }?.address.orEmpty()
    return MessageModel(
        id = if (mms) -key.sourceId else key.sourceId, threadId = threadId ?: -1,
        sender = sender, content = if (mms) parts.filter { it.mimeType?.startsWith("text/") == true }
            .mapNotNull { it.text }.joinToString("\n") else body.orEmpty(),
        timestamp = timestamp ?: 0, subId = subscriptionId ?: -1,
        isRead = read == 1, isReceived = boxType == 1,
        imageUris = parts.filter { it.mimeType?.startsWith("image/") == true }.mapNotNull { it.attachment?.localUri },
        mmsSubject = decodedSubject ?: subject, isMms = mms,
        mmsDownloadPending = mms && pduType == 130,
    )
}
