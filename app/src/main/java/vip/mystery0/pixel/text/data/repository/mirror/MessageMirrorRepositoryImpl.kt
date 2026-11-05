package vip.mystery0.pixel.text.data.repository.mirror

import vip.mystery0.pixel.text.data.repository.mms.mmsContentFingerprint
import vip.mystery0.pixel.text.data.repository.mms.mmsAttachmentSummary
import vip.mystery0.pixel.text.domain.parser.mms.MmsMimeTypes
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import vip.mystery0.pixel.text.data.db.mirror.*
import vip.mystery0.pixel.text.data.source.mirror.MirrorAttachmentStore
import vip.mystery0.pixel.text.data.source.mirror.ProviderRowSnapshot
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.mirror.*
import vip.mystery0.pixel.text.domain.model.search.MessageSearchBatch
import vip.mystery0.pixel.text.domain.model.search.MessageSearchRequest
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository

class MessageMirrorRepositoryImpl(
    private val database: MessageMirrorDatabase,
    private val store: MirrorAttachmentStore,
) : MessageMirrorRepository {
    private val dao get() = database.mirrorDao()
    private val searchDao get() = database.searchDao()

    override suspend fun getMessage(key: SourceMessageKey): MirrorMessageModel? =
        dao.get(key.transport.name, key.sourceId)?.toModel()

    override fun observeMessage(key: SourceMessageKey): Flow<MirrorMessageModel?> =
        dao.observeMessage(key.transport.name, key.sourceId).map { it?.toModel() }
            .distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun observeMessagesByThread(threadId: Long, limit: Int, offset: Int): Flow<List<MirrorMessageModel>> =
        dao.observeThread(threadId, limit.coerceAtLeast(1), offset.coerceAtLeast(0))
            .map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)

    override suspend fun queryThreadMessages(threadId: Long, limit: Int, offset: Int,
        filter: vip.mystery0.pixel.text.domain.repository.ConversationContentFilter): List<MirrorMessageModel> =
        withContext(Dispatchers.IO) {
            require(limit >= 0 && offset >= 0)
            val filterValue = when (filter) {
                vip.mystery0.pixel.text.domain.repository.ConversationContentFilter.ALL -> 0
                vip.mystery0.pixel.text.domain.repository.ConversationContentFilter.NORMAL -> 1
                vip.mystery0.pixel.text.domain.repository.ConversationContentFilter.SPAM -> 2
            }
            dao.threadPage(threadId, filterValue, limit, offset).map { it.toModel() }
        }

    override fun observeAllMessages(): Flow<List<MirrorMessageModel>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)

    override fun observeMmsMessages(): Flow<List<MirrorMessageModel>> =
        dao.observeMms().map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.IO)

    override suspend fun getConversationMessages(threadIds: List<Long>): List<ConversationMessageSnapshot> =
        withContext(Dispatchers.IO) {
            buildList {
                // 分批限制绑定参数数量；短信只读轻量字段，彩信保留完整派生校验语义。
                for (batch in threadIds.distinct().chunked(500)) {
                    currentCoroutineContext().ensureActive()
                    addAll(dao.conversationSms(batch).map { it.toConversationSnapshot() })
                    addAll(dao.conversationMms(batch).map { it.toConversationSnapshot() })
                }
            }.sortedWith(compareByDescending<ConversationMessageSnapshot> { it.message.timestamp }.thenByDescending { it.localId })
        }

    override suspend fun getConversationMessagesByIds(
        messageIds: Collection<Long>,
    ): List<ConversationMessageSnapshot> = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext emptyList()
        val ids = messageIds.toSet()
        buildList {
            for (batch in ids.filter { it >= 0 }.chunked(500)) {
                currentCoroutineContext().ensureActive()
                addAll(dao.conversationSmsByIds(batch).map { it.toConversationSnapshot() })
            }
            for (batch in ids.filter { it < 0 && it != Long.MIN_VALUE }.map { -it }.chunked(500)) {
                currentCoroutineContext().ensureActive()
                addAll(dao.conversationMmsByIds(batch).map { it.toConversationSnapshot() })
            }
        }.sortedWith(compareByDescending<ConversationMessageSnapshot> { it.message.timestamp }.thenByDescending { it.localId })
    }

    override suspend fun queryConversations(query: ConversationListQuery): List<ConversationModel> = withContext(Dispatchers.IO) {
        val sql = MirrorConversationQuery.build(query)
        if (query.threadIds?.isEmpty() == true || query.limit == 0) return@withContext emptyList()
        database.withTransaction {
            val rows = dao.filteredConversations(sql)
            val mms = buildMap<Long, MessageModel> {
                // 聚合和最新彩信读取使用同一事务，避免两次查询之间删除或更新消息。
                for (batch in rows.filter { it.latestIsMms }.map { it.localId }.chunked(500)) {
                    currentCoroutineContext().ensureActive()
                    dao.conversationMmsByLocalIds(batch).forEach { record ->
                        put(record.message.localId, record.toModel().toMessageModel())
                    }
                }
            }
            rows.map { row ->
                currentCoroutineContext().ensureActive()
                val latestMms = if (row.latestIsMms) requireNotNull(mms[row.localId]) else null
                ConversationModel(
                    threadId = row.threadId,
                    address = latestMms?.sender ?: row.address.orEmpty(),
                    snippet = latestMms?.let { it.mmsSummary ?: it.content } ?: row.snippet.orEmpty(),
                    timestamp = row.timestamp ?: 0,
                    unreadCount = row.unreadCount,
                    isMms = row.latestIsMms,
                    hasMms = row.containsMms,
                )
            }
        }
    }

    private fun MirrorConversationSmsRow.toConversationSnapshot(): ConversationMessageSnapshot {
        val message = MessageModel(
            id = sourceId, threadId = threadId ?: -1,
            sender = address.orEmpty(), content = body.orEmpty(),
            timestamp = timestamp ?: 0, subId = subscriptionId ?: -1,
            isRead = read == 1, isReceived = boxType == 1,
        )
        return ConversationMessageSnapshot(localId, message, message.sender, originalDate ?: 0, dateSent ?: 0, revision)
    }

    private suspend fun MirrorMessageRecord.toConversationSnapshot(): ConversationMessageSnapshot {
        val snapshot = toModel()
        val addresses = snapshot.addresses.filter {
            it.type in setOf(137, 151, 130, 129) &&
                !it.address.isNullOrBlank() && it.address != "insert-address-token"
        }
        return ConversationMessageSnapshot(
            snapshot.localId, snapshot.toMessageModel(),
            (addresses.firstOrNull { it.type == 137 } ?: addresses.firstOrNull())?.address.orEmpty(),
            snapshot.rawValues.firstOrNull { it.column == "date" }?.value?.toLongOrNull() ?: 0,
            snapshot.rawValues.firstOrNull { it.column == "date_sent" }?.value?.toLongOrNull() ?: 0,
            snapshot.revision,
        )
    }

    override fun observeThreadChanges(threadId: Long): Flow<Unit> =
        dao.observeThreadChanges(threadId).map { }.flowOn(Dispatchers.IO)

    override fun observeConversationChanges(): Flow<Unit> =
        dao.observeConversationChanges(SimpleSQLiteQuery("SELECT 1")).map { }.flowOn(Dispatchers.IO)

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
        return combine(database.syncDao().observeStates(), counts) { allStates, count ->
            // 判定迁移与跨库写入恢复共用进度存储，但不属于系统消息同步集合。
            val states = allStates.filter { it.collection in setOf("SMS", "MMS", "THREADS", "CANONICAL", "ROUND", "TARGETED") }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun searchMessages(request: MessageSearchRequest): Flow<MessageSearchBatch> {
        val searchQuery = MirrorSearchQuery.buildSearchQuery(request)
        val unreadyQuery = MirrorSearchQuery.buildUnreadyCountQuery(request)
        val trigger = SimpleSQLiteQuery("SELECT 1")

        val searchFlow = searchDao.observeTableChanges(trigger)
            .mapLatest {
                searchDao.searchBatch(searchQuery, unreadyQuery, request.hasEffectiveQuery)
            }

        return combine(searchFlow, observeSyncState()) { searchResult, syncState ->
            val syncIncomplete = when {
                syncState.phase == MirrorInitializationPhase.NOT_STARTED -> true
                !syncState.completedCollections.containsAll(setOf("SMS", "MMS")) -> true
                syncState.incompleteStructureCount > 0 -> true
                syncState.collections.any { it.collection in setOf("SMS", "MMS") && it.error != null } -> true
                else -> false
            }
            val textIncomplete = request.hasEffectiveQuery && searchResult.unreadyCount > 0
            val incomplete = syncIncomplete || textIncomplete

            val messages = searchResult.rows.map { it.toSearchMessageModel(request) }
            MessageSearchBatch(messages = messages, incomplete = incomplete)
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    private fun MirrorSearchRow.toSearchMessageModel(request: MessageSearchRequest): MessageModel {
        val isMms = transport == "MMS"
        val preferredSubject = mmsDecodedSubject ?: mmsSubject
        val fallbackSummary = preferredSubject ?: if (pduType == 130) "等待下载彩信" else "彩信"
        val summary = mmsSummary ?: fallbackSummary

        val content = if (isMms) {
            if (request.hasEffectiveQuery) {
                mmsHitBody?.takeIf { it.isNotBlank() }
                    ?: mmsHitSubject?.takeIf { it.isNotBlank() }
                    ?: mmsEffectiveBody?.takeIf { it.isNotBlank() }
                    ?: preferredSubject.orEmpty()
            } else {
                mmsEffectiveBody?.takeIf { it.isNotBlank() }
                    ?: preferredSubject?.takeIf { it.isNotBlank() }
                    ?: summary
            }
        } else {
            smsBody.orEmpty()
        }

        return MessageModel(
            id = if (isMms) -sourceId else sourceId,
            threadId = threadId ?: -1,
            sender = address.orEmpty(),
            content = content,
            timestamp = timestamp ?: 0,
            subId = subscriptionId ?: -1,
            isRead = read == 1,
            isReceived = boxType == 1,
            mmsSubject = if (isMms) preferredSubject else null,
            isMms = isMms,
            mmsDownloadPending = isMms && pduType == 130,
            mmsSummary = if (isMms) summary else null,
        )
    }

    private suspend fun MirrorMessageRecord.toModel(): MirrorMessageModel {
        val attachmentMap = attachments.associateBy { it.partId }
        val model = MirrorMessageModel(
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
        val derived = mmsText?.takeIf { it.version == vip.mystery0.pixel.text.data.repository.mms.MMS_TEXT_INDEX_VERSION && it.fingerprint == mmsContentFingerprint(model) }
        return model.copy(mmsSummary = derived?.summary, mmsSearchableText = derived?.searchableText)
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
        sender = sender, content = if (mms) mmsSearchableText ?: decodedSubject ?: subject ?: "" else body.orEmpty(),
        timestamp = timestamp ?: 0, subId = subscriptionId ?: -1,
        isRead = read == 1, isReceived = boxType == 1,
        imageUris = parts.filter { it.mimeType?.startsWith("image/") == true }.mapNotNull { it.attachment?.localUri },
        mmsSubject = if (mms) decodedSubject ?: subject else null, isMms = mms,
        mmsDownloadPending = mms && pduType == 130,
        mmsSummary = if (mms) mmsSummary ?: decodedSubject ?: subject ?: if (pduType == 130) "等待下载彩信"
            else mmsAttachmentSummary(parts.map { MmsMimeTypes.classify(it.mimeType) }) else null,
    )
}
