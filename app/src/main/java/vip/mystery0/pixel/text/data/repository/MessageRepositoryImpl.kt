package vip.mystery0.pixel.text.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.onStart
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
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.ConversationContentFilter
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.data.repository.mirror.toMessageModel
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.repository.MessageSearchFilter
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.notification.SmsNotificationHelper
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration

private const val SPAM_THRESHOLD = 0.7f
private const val FILTERED_MESSAGE_QUERY_STEP = 100
private const val CONVERSATION_FILTER_CHUNK_SIZE = 200
private const val SPAM_CHANGE_DEBOUNCE_MILLIS = 300L

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

    init { mmsTextIndexer.start() }

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

    @OptIn(FlowPreview::class)
    override fun getAllConversations(): Flow<List<ConversationModel>> = flow {
        if (!conversationCacheRepository.isCacheReady()) {
            conversationCacheRepository.startObserving()
        }

        emitAll(
            combine(
                conversationCacheRepository.observeAllConversations(),
                spamRepository.observeChanges()
                    .debounce(SPAM_CHANGE_DEBOUNCE_MILLIS)
                    .onStart { emit(Unit) },
                settingsRepository.settings,
            ) { conversations, _, settings -> conversations to settings }
                .map { (conversations, settings) ->
                    val archivedThreadIds = archiveDao.getArchivedThreadIds().toSet()
                    val activeConversations = conversations.filter {
                        it.threadId !in archivedThreadIds
                    }
                    val visibleConversations = if (settings.spamIsolationEnabled) {
                        enrichWithSenderProfiles(
                            fetchConversationDetails(
                                activeConversations.map { it.threadId },
                                ConversationContentFilter.NORMAL,
                            )
                        ).sortedByDescending { it.timestamp }
                    } else {
                        enrichWithSenderProfiles(activeConversations)
                    }
                    visibleConversations.map {
                        it.copy(
                            displayName = contactDataSource.getDisplayName(it.address)
                                ?: it.displayName,
                        )
                    }
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
            val threadIds = spamRepository.getSpamThreadIds(SPAM_THRESHOLD, limit, offset)
            if (threadIds.isEmpty()) {
                emit(emptyList())
                return@flow
            }
            val contentFilter = if (settingsRepository.isSpamIsolationEnabled()) {
                ConversationContentFilter.SPAM
            } else {
                ConversationContentFilter.ALL
            }
            emit(
                enrichWithSenderProfiles(fetchConversationDetails(threadIds, contentFilter))
                    .sortedByDescending { it.timestamp }
            )
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun searchMessages(query: String, filter: MessageSearchFilter): Flow<List<MessageModel>> =
        mirror.observeAllMessages().mapLatest { rows -> rows.map { currentCoroutineContext().ensureActive(); it.toMessageModel() }.filter { message ->
            currentCoroutineContext().ensureActive()
            (query.isBlank() || message.content.contains(query, true) || message.mmsSubject.orEmpty().contains(query, true) || message.mmsSummary.orEmpty().contains(query, true)) &&
                (!filter.unreadOnly || !message.isRead) &&
                (filter.simSubId == null || message.subId == filter.simSubId) &&
                (!filter.mmsOnly || message.isMms) &&
                (filter.contactAddress.isNullOrBlank() || contactDataSource.matchesAddress(filter.contactAddress, message.sender))
        }.map { message -> message.copy(simName = telephonyDataSource.getSimName(message.subId)) }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun getMessagesByThread(
        threadId: Long, limit: Int, offset: Int, contentFilter: ConversationContentFilter,
    ): Flow<List<MessageModel>> = flow {
        if (limit <= 0) { emit(emptyList()); return@flow }
        val result = mutableListOf<MessageModel>()
        var sourceOffset = if (contentFilter == ConversationContentFilter.ALL) offset else 0
        var skipped = 0
        while (result.size < limit) {
            val batchSize = if (contentFilter == ConversationContentFilter.ALL) minOf(limit - result.size, 200) else 200
            val rows = mirror.observeMessagesByThread(threadId, batchSize, sourceOffset).first().map { it.toMessageModel() }
            if (rows.isEmpty()) break
            val spamIds = spamRepository.getSpamMessageIds(rows.map { it.id }, SPAM_THRESHOLD)
            for (message in rows) {
                if (!contentFilter.includes(message.id in spamIds)) continue
                if (contentFilter != ConversationContentFilter.ALL && skipped++ < offset) continue
                result += message.copy(simName = telephonyDataSource.getSimName(message.subId),
                    parsedResult = parseMessage(message.sender, message.content), spamScore = spamRepository.getScore(message.id) ?: -1f)
                if (result.size == limit) break
            }
            sourceOffset += rows.size
            if (rows.size < batchSize) break
        }
        emit(result)
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
        return conversations.map { conversation ->
            val profile = profiles[conversation.address]
            conversation.copy(
                displayName = contactDataSource.getDisplayName(conversation.address)
                    ?: profile?.displayName
                    ?: conversation.displayName,
                avatarPath = profile?.avatarPath,
                avatarSha256 = profile?.avatarSha256,
            )
        }
    }

    private suspend fun fetchConversationDetails(
        threadIds: List<Long>, contentFilter: ConversationContentFilter = ConversationContentFilter.ALL,
    ): List<ConversationModel> {
        val selected = threadIds.toSet()
        val messages = localMessages().filter { it.threadId in selected }
        val spamIds = if (contentFilter == ConversationContentFilter.ALL) emptySet() else
            messages.map { it.id }.chunked(500).flatMap { spamRepository.getSpamMessageIds(it, SPAM_THRESHOLD) }.toSet()
        return messages.filter { contentFilter.includes(it.id in spamIds) }.groupBy { it.threadId }.map { (thread, rows) ->
            val latest = rows.first()
            ConversationModel(
                threadId = thread, address = latest.sender,
                snippet = latest.mmsSummary ?: latest.content,
                timestamp = latest.timestamp, unreadCount = rows.count { !it.isRead },
                isMms = latest.isMms, hasMms = rows.any { it.isMms },
            )
        }.sortedByDescending { it.timestamp }
    }
    private fun ConversationContentFilter.includes(isSpam: Boolean): Boolean = when (this) {
        ConversationContentFilter.ALL -> true
        ConversationContentFilter.NORMAL -> !isSpam
        ConversationContentFilter.SPAM -> isSpam
    }

    private fun parseMessage(address: String, content: String): ParsedResult {
        if (!settingsRepository.isSmartCardEnabled()) return ParsedResult.None
        return messageParser.parse(address, content)
    }
}
