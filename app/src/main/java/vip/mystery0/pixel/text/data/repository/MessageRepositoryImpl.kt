package vip.mystery0.pixel.text.data.repository


import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.domain.model.mirror.ConversationArchiveFilter
import vip.mystery0.pixel.text.data.db.toArchivedConversationEntity
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.data.source.requireSmsAccess
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
    database: MessageMirrorDatabase,
    val conversationCacheRepository: ConversationCacheRepository,
    private val senderProfileRepository: SenderProfileRepository,
    private val verificationCodeRepository: VerificationCodeRepository,
    private val mirror: MessageMirrorRepository,
    private val context: Context
) : MessageRepository {



    private val archiveDao = database.archivedConversationDao()

    override fun startCacheObserving() {
        conversationCacheRepository.startObserving()
    }

    override suspend fun isCacheReady(): Boolean = conversationCacheRepository.isCacheReady()

    override suspend fun forceSyncConversations() {
        withContext(Dispatchers.IO) {
            conversationCacheRepository.fullSync()
        }
    }

    override suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        // 用户主动刷新等待完整确认，包括增量无法识别的删除。
        conversationCacheRepository.refreshIncremental(reconcileIfRequired = true)
    }

    override fun getAllConversations(): Flow<List<ConversationModel>> = channelFlow {
        // 首屏只读本地镜像，不等待 Provider 同步；同步写入镜像后会话表变化会自动重新发布。
        launch {
            try {
                spamRepository.ensureReady()
                conversationCacheRepository.refreshIncremental(reconcileIfRequired = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 同步失败不影响已有数据展示，由镜像 Worker 退避重试。
                Log.w("MessageRepository", "conversation background sync failed error=${error.javaClass.simpleName}")
                MessageMirrorScheduler(context).schedule()
            }
        }
        combine(
            mirror.observeConversationChanges(),
            settingsRepository.settings,
            contactDataSource.changes,
        ) { _, settings, _ -> settings.spamIsolationEnabled }
            .conflate()
            .map { isolationEnabled ->
                // 表变化只触发一次聚合，避免先读取整张摘要列表再重新过滤分组。
                enrichWithSenderProfiles(
                    mirror.queryConversations(
                        ConversationListQuery(
                            contentFilter = if (isolationEnabled) {
                                ConversationContentFilter.NORMAL
                            } else ConversationContentFilter.ALL,
                            archiveFilter = ConversationArchiveFilter.UNARCHIVED,
                            incomingUnreadOnly = !isolationEnabled,
                        )
                    )
                )
            }
            .collect { send(it) }
    }.flowOn(Dispatchers.IO)

    override fun getArchivedConversations(limit: Int, offset: Int): Flow<List<ConversationModel>> =
        flow {
            val conversations = mirror.queryConversations(
                ConversationListQuery(
                    archiveFilter = ConversationArchiveFilter.ARCHIVED,
                    limit = limit,
                    offset = offset,
                )
            )
            emit(enrichWithSenderProfiles(conversations))
        }.flowOn(Dispatchers.IO)

    override fun getSpamConversations(limit: Int, offset: Int): Flow<List<ConversationModel>> =
        flow {
            require(limit >= 0 && offset >= 0)
            spamRepository.ensureReady()
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
            val enriched = enrichWithSenderProfiles(conversations).sortedByDescending { it.timestamp }
            emit(enriched)
        }.flowOn(Dispatchers.IO)

    override suspend fun archiveConversations(conversations: List<ConversationModel>) {
        if (conversations.isEmpty()) return
        withContext(Dispatchers.IO) {
            val archivedAt = System.currentTimeMillis()
            archiveDao.archive(
                conversations.map { it.toArchivedConversationEntity(archivedAt) }
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
            context.requireSmsAccess()
            telephonyDataSource.deleteThreads(threadIds)
            val existingSmsThreadIds = telephonyDataSource.existingSmsThreadIds(threadIds)
            verificationCodeRepository.deleteThreadIds(threadIds - existingSmsThreadIds)
            archiveDao.unarchive(threadIds)
            conversationCacheRepository.syncLocalChanges()
            SmsNotificationHelper.cancelThreadNotifications(context, threadIds)
            SmartspacerIntegration.notifyChanged(context)
        }
    }

    override suspend fun deleteMessages(messageIds: Set<Long>): Int {
        if (messageIds.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            context.requireSmsAccess()
            val threadIds = telephonyDataSource.getThreadIdsForMessages(messageIds)
            val deletedCount = telephonyDataSource.deleteMessages(messageIds)
            val requestedSmsIds = messageIds.filterTo(mutableSetOf()) { it > 0 }
            val existingSmsIds = telephonyDataSource.existingSmsMessageIds(requestedSmsIds)
            verificationCodeRepository.deleteMessageIds(requestedSmsIds - existingSmsIds)
            spamRepository.delete(messageIds)
            conversationCacheRepository.syncLocalChanges()
            SmsNotificationHelper.cancelThreadNotifications(context, threadIds)
            SmartspacerIntegration.notifyChanged(context)
            deletedCount
        }
    }

    override suspend fun markMessagesAsRead(messageIds: Set<Long>): Int {
        if (messageIds.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            context.requireSmsAccess()
            val threadIds = telephonyDataSource.getThreadIdsForMessages(messageIds)
            val updatedCount = telephonyDataSource.markMessagesAsRead(messageIds)
            conversationCacheRepository.syncLocalChanges()
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

    override suspend fun markThreadAsRead(threadId: Long) {
        markThreadsAsRead(setOf(threadId))
    }

    override suspend fun markThreadsAsRead(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            context.requireSmsAccess()
            telephonyDataSource.markThreadsAsRead(threadIds)
            conversationCacheRepository.syncLocalChanges()
            SmsNotificationHelper.cancelThreadNotifications(context, threadIds)
            SmartspacerIntegration.notifyChanged(context)
        }
    }

    override suspend fun markThreadAsUnread(threadId: Long) {
        if (threadId < 0) return
        withContext(Dispatchers.IO) {
            context.requireSmsAccess()
            telephonyDataSource.markLatestIncomingMessageAsUnread(threadId)
            conversationCacheRepository.syncLocalChanges()
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

    private fun parseMessage(address: String, content: String): ParsedResult {
        if (!settingsRepository.isSmartCardEnabled()) return ParsedResult.None
        return messageParser.parse(address, content)
    }
}
