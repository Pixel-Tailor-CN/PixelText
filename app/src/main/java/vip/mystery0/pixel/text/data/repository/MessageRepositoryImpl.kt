package vip.mystery0.pixel.text.data.repository


import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.db.toArchivedConversationEntity
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.data.source.MmsConversationRow
import vip.mystery0.pixel.text.data.source.MmsMessageRow
import vip.mystery0.pixel.text.data.source.SmsConversationRow
import vip.mystery0.pixel.text.data.source.SmsMessageRow
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.mirror.ConversationListQuery
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.ConversationContentFilter
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.data.repository.mirror.toMessageModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import vip.mystery0.pixel.text.domain.model.search.MessageSearchBatch
import vip.mystery0.pixel.text.domain.model.search.MessageSearchRequest
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.notification.SmsNotificationHelper
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration


class MessageRepositoryImpl(
    private val telephonyDataSource: TelephonyDataSource,
    private val contactDataSource: ContactDataSource,
    private val messageParser: MessageParser,
    private val spamRepository: SpamRepository,
    private val settingsRepository: AppSettingsRepository,
    private val archiveDatabase: ConversationArchiveDatabase,
    val conversationCacheRepository: ConversationCacheRepository,
    private val senderProfileRepository: SenderProfileRepository,
    private val verificationCodeRepository: VerificationCodeRepository,
    private val mirror: MessageMirrorRepository,
    private val mmsTextIndexer: vip.mystery0.pixel.text.data.repository.mms.MmsTextIndexer,
    private val context: Context
) : MessageRepository {



    private val archiveDao = archiveDatabase.archivedConversationDao()

    override fun startCacheObserving() {
        conversationCacheRepository.startObserving()
    }

    override suspend fun isCacheReady(): Boolean = conversationCacheRepository.isCacheReady()

    override suspend fun forceSyncConversations() {
        withContext(Dispatchers.IO) {
            val archivedThreadIds = archiveDao.getArchivedThreadIds().toSet()
            conversationCacheRepository.fullSync(archivedThreadIds)
        }
    }

    override suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        conversationCacheRepository.refreshIncremental()
    }

    override fun getAllConversations(): Flow<List<ConversationModel>> = flow {
        conversationCacheRepository.refreshIncremental(requireComplete = true)
        spamRepository.ensureReady()
        emitAll(
            combine(
                mirror.observeConversationChanges(),
                settingsRepository.settings,
                contactDataSource.changes,
                archiveDao.observeArchivedThreadIds(),
            ) { _, settings, _, archived -> settings.spamIsolationEnabled to archived.toSet() }
                .conflate()
                .map { (isolationEnabled, archivedThreadIds) ->
                    // 表变化只触发一次聚合，避免先读取整张摘要列表再重新过滤分组。
                    val preparationStarted = android.os.SystemClock.elapsedRealtime()
                    val visibleConversations = enrichWithSenderProfiles(
                        mirror.queryConversations(
                            ConversationListQuery(
                                contentFilter = if (isolationEnabled) {
                                    ConversationContentFilter.NORMAL
                                } else ConversationContentFilter.ALL,
                                excludedThreadIds = archivedThreadIds,
                                incomingUnreadOnly = !isolationEnabled,
                            )
                        )
                    )
                    if (vip.mystery0.pixel.text.BuildConfig.DEBUG) {
                        android.util.Log.d("ConversationLoad", "conversation load published count=${visibleConversations.size} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - preparationStarted}")
                    }
                    visibleConversations
                }
        )
    }.flowOn(Dispatchers.IO)

    override fun getArchivedConversations(limit: Int, offset: Int): Flow<List<ConversationModel>> =
        flow {
            val threadIds = archiveDao.getArchivedThreadIds(limit, offset)
            if (threadIds.isEmpty()) {
                emit(emptyList())
                return@flow
            }

            val conversations = enrichWithSenderProfiles(fetchConversationDetails(threadIds))
                .sortedByDescending { it.timestamp }
            // 本地暂缺不代表系统删除；首次镜像或失败期间必须保留归档选择。
            emit(conversations)
        }.flowOn(Dispatchers.IO)

    override fun getSpamConversations(limit: Int, offset: Int): Flow<List<ConversationModel>> =
        flow {
            require(limit >= 0 && offset >= 0)
            spamRepository.ensureReady()
            val started = android.os.SystemClock.elapsedRealtime()
            // SQL 先按有效骚扰消息选会话页，再按隔离设置汇总可见消息。
            val conversations = mirror.queryConversations(
                ConversationListQuery(
                    contentFilter = if (settingsRepository.isSpamIsolationEnabled()) {
                        ConversationContentFilter.SPAM
                    } else ConversationContentFilter.ALL,
                    onlySpamThreads = true,
                    limit = limit,
                    offset = offset,
                )
            )
            val aggregatedAt = android.os.SystemClock.elapsedRealtime()
            val enriched = enrichWithSenderProfiles(conversations).sortedByDescending { it.timestamp }
            if (vip.mystery0.pixel.text.BuildConfig.DEBUG) {
                val finishedAt = android.os.SystemClock.elapsedRealtime()
                android.util.Log.d(
                    "SpamListLoad",
                    "spam list loaded page_count=${enriched.size} " +
                        "aggregate_ms=${aggregatedAt - started} " +
                        "profiles_ms=${finishedAt - aggregatedAt} total_ms=${finishedAt - started}",
                )
            }
            emit(enriched)
        }.flowOn(Dispatchers.IO)

    override fun searchConversations(query: String): Flow<List<ConversationModel>> = flow {
        val threadIds = localMessages().filter { it.sender.contains(query, true) || it.content.contains(query, true) }.map { it.threadId }.distinct()
        if (threadIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val sorted = enrichWithSenderProfiles(fetchConversationDetails(threadIds))
            .sortedByDescending { it.timestamp }
        emit(sorted)
    }.flowOn(Dispatchers.IO)

    override suspend fun archiveConversations(conversations: List<ConversationModel>) {
        if (conversations.isEmpty()) return
        withContext(Dispatchers.IO) {
            val archivedAt = System.currentTimeMillis()
            archiveDao.archive(
                conversations.map { conversation ->
                    conversation.copy(
                        displayName = contactDataSource.getDisplayName(conversation.address),
                        avatarPath = null,
                        avatarSha256 = null,
                    ).toArchivedConversationEntity(archivedAt)
                }
            )
            SmartspacerIntegration.notifyChanged(context)
        }
    }

    override suspend fun unarchiveThreads(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            archiveDao.unarchive(threadIds)
            SmartspacerIntegration.notifyChanged(context)
        }
    }

    override suspend fun deleteThreads(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            telephonyDataSource.deleteThreads(threadIds)
            val existingSmsThreadIds = telephonyDataSource.existingSmsThreadIds(threadIds)
            verificationCodeRepository.deleteThreadIds(threadIds - existingSmsThreadIds)
            archiveDao.unarchive(threadIds)
            conversationCacheRepository.syncThreads(threadIds.toList())
            SmsNotificationHelper.cancelThreadNotifications(context, threadIds)
            SmartspacerIntegration.notifyChanged(context)
        }
    }

    override suspend fun deleteMessages(messageIds: Set<Long>): Int {
        if (messageIds.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val threadIds = telephonyDataSource.getThreadIdsForMessages(messageIds)
            val deletedCount = telephonyDataSource.deleteMessages(messageIds)
            val requestedSmsIds = messageIds.filterTo(mutableSetOf()) { it > 0 }
            val existingSmsIds = telephonyDataSource.existingSmsMessageIds(requestedSmsIds)
            verificationCodeRepository.deleteMessageIds(requestedSmsIds - existingSmsIds)
            spamRepository.delete(messageIds)
            conversationCacheRepository.syncThreads(threadIds.toList())
            SmsNotificationHelper.cancelThreadNotifications(context, threadIds)
            SmartspacerIntegration.notifyChanged(context)
            deletedCount
        }
    }

    override suspend fun markMessagesAsRead(messageIds: Set<Long>): Int {
        if (messageIds.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val threadIds = telephonyDataSource.getThreadIdsForMessages(messageIds)
            val updatedCount = telephonyDataSource.markMessagesAsRead(messageIds)
            conversationCacheRepository.syncThreads(threadIds.toList())
            SmsNotificationHelper.cancelThreadNotifications(context, threadIds)
            SmartspacerIntegration.notifyChanged(context)
            updatedCount
        }
    }

    override fun searchMessages(request: MessageSearchRequest): Flow<MessageSearchBatch> = flow {
        spamRepository.ensureReady()
        // 判定列与搜索原文同在镜像库，镜像失效观察已经覆盖分类更新。
        emitAll(mirror.searchMessages(request).map { batch ->
            val scores = spamRepository.getScores(batch.messages.map { it.id })
            batch.copy(messages = batch.messages.map { message ->
                message.copy(simName = telephonyDataSource.getSimName(message.subId),
                    spamScore = scores[message.id] ?: -1f)
            })
        })
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun getMessagesByThread(
        threadId: Long, limit: Int, offset: Int, contentFilter: ConversationContentFilter,
    ): Flow<List<MessageModel>> = flow {
        if (limit <= 0) { emit(emptyList()); return@flow }
        spamRepository.ensureReady()
        // 先按持久化状态过滤和分页，仅加载当前页的正文及彩信关系。
        val messages = mirror.queryThreadMessages(threadId, limit, offset, contentFilter).map { it.toMessageModel() }
        val scores = spamRepository.getScores(messages.map { it.id })
        emit(messages.map { message ->
            message.copy(simName = telephonyDataSource.getSimName(message.subId),
                parsedResult = parseMessage(message.sender, message.content),
                spamScore = scores[message.id] ?: -1f)
        })
    }.flowOn(Dispatchers.IO)

    override fun getMessages(): Flow<List<MessageModel>> = flow {
        emit(localMessages().map { it.copy(parsedResult = parseMessage(it.sender, it.content)) })
    }.flowOn(Dispatchers.IO)

    private suspend fun localMessages(): List<MessageModel> = mirror.observeAllMessages().first().map {
        it.toMessageModel().let { message -> message.copy(simName = telephonyDataSource.getSimName(message.subId)) }
    }.sortedByDescending { it.timestamp }
    override suspend fun markThreadAsRead(threadId: Long) {
        markThreadsAsRead(setOf(threadId))
    }

    override suspend fun markThreadsAsRead(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            telephonyDataSource.markThreadsAsRead(threadIds)
            conversationCacheRepository.syncThreads(threadIds.toList())
            SmsNotificationHelper.cancelThreadNotifications(context, threadIds)
            SmartspacerIntegration.notifyChanged(context)
        }
    }

    override suspend fun markThreadAsUnread(threadId: Long) {
        if (threadId < 0) return
        withContext(Dispatchers.IO) {
            telephonyDataSource.markLatestIncomingMessageAsUnread(threadId)
            conversationCacheRepository.syncThreads(listOf(threadId))
            SmartspacerIntegration.notifyChanged(context)
        }
    }

    private suspend fun enrichWithSenderProfiles(
        conversations: List<ConversationModel>,
    ): List<ConversationModel> {
        val profiles = senderProfileRepository.findByNumbers(conversations.map { it.address })
        val contactNames = contactDataSource.getCachedDisplayNames(conversations.map { it.address })
        return conversations.map { conversation ->
            val profile = profiles[conversation.address]
            conversation.copy(
                displayName = contactNames[conversation.address]
                    ?: profile?.displayName
                    ?: conversation.displayName,
                avatarPath = profile?.avatarPath,
                avatarSha256 = profile?.avatarSha256,
            )
        }
    }

    private suspend fun fetchConversationDetails(
        threadIds: List<Long>,
    ): List<ConversationModel> {
        if (threadIds.isEmpty()) return emptyList()
        // 归档等入口只读取每个线程最终展示的正文，历史消息只参与 SQL 聚合。
        return mirror.queryConversations(ConversationListQuery(threadIds = threadIds))
    }

    private fun parseMessage(address: String, content: String): ParsedResult {
        if (!settingsRepository.isSmartCardEnabled()) return ParsedResult.None
        return messageParser.parse(address, content)
    }
}
