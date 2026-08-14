package vip.mystery0.pixel.text.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    @OptIn(FlowPreview::class)
    override fun getAllConversations(): Flow<List<ConversationModel>> = flow {
        if (!conversationCacheRepository.isCacheReady()) {
            val archivedThreadIds = archiveDao.getArchivedThreadIds().toSet()
            conversationCacheRepository.fullSync(archivedThreadIds)
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
                        activeConversations
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
            val missingThreadIds = threadIds.toSet() - conversations.map { it.threadId }.toSet()
            if (missingThreadIds.isNotEmpty()) {
                archiveDao.unarchive(missingThreadIds)
            }
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
        val threadIds = telephonyDataSource.searchConversationThreadIds(query)
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

    override fun searchMessages(
        query: String,
        filter: MessageSearchFilter
    ): Flow<List<MessageModel>> = flow {
        val smsMessages = if (filter.mmsOnly) {
            emptyList()
        } else {
            telephonyDataSource.searchSmsMessages(
                query = query,
                unreadOnly = filter.unreadOnly,
                simSubId = filter.simSubId
            ).map { it.toMessageModel(parsedResult = ParsedResult.None) }
        }
        val mmsMessages = telephonyDataSource.searchMmsMessages(
            query = query,
            unreadOnly = filter.unreadOnly,
            simSubId = filter.simSubId
        )
            .map { it.toMessageModel(parsedResult = ParsedResult.None) }

        val filteredMessages = (smsMessages + mmsMessages)
            .filter { message ->
                val selectedContactAddress = filter.contactAddress
                if (selectedContactAddress.isNullOrBlank()) {
                    true
                } else {
                    contactDataSource.matchesAddress(
                        selectedAddress = selectedContactAddress,
                        candidateAddress = message.sender
                    )
                }
            }
            .sortedByDescending { it.timestamp }

        emit(filteredMessages)
    }.flowOn(Dispatchers.IO)

    override fun getMessagesByThread(
        threadId: Long,
        limit: Int,
        offset: Int,
        contentFilter: ConversationContentFilter,
    ): Flow<List<MessageModel>> = flow {
        val visibleNeeded = limit + offset
        var queryLimit = if (contentFilter == ConversationContentFilter.ALL) {
            visibleNeeded
        } else {
            visibleNeeded.coerceAtLeast(FILTERED_MESSAGE_QUERY_STEP)
        }
        var smsRows: List<SmsMessageRow>
        var mmsRows: List<MmsMessageRow>
        var spamMessageIds: Set<Long>
        var filteredMessageIds: List<Long>

        while (true) {
            smsRows = telephonyDataSource.getSmsMessagesByThread(threadId, queryLimit)
            mmsRows = telephonyDataSource.getMmsMessagesByThread(threadId, queryLimit)
            val messageIds = smsRows.map { it.id } + mmsRows.map { -it.mmsId }
            spamMessageIds = spamRepository.getSpamMessageIds(messageIds, SPAM_THRESHOLD)
            filteredMessageIds = messageIds.filter { messageId ->
                contentFilter.includes(messageId in spamMessageIds)
            }
            val sourceExhausted = smsRows.size < queryLimit && mmsRows.size < queryLimit
            if (filteredMessageIds.size >= visibleNeeded || sourceExhausted ||
                contentFilter == ConversationContentFilter.ALL
            ) {
                break
            }
            queryLimit += FILTERED_MESSAGE_QUERY_STEP
        }

        val smsMessages = smsRows
            .filter { contentFilter.includes(it.id in spamMessageIds) }
            .map { row ->
                row.toMessageModel(
                    parsedResult = parseMessage(row.address, row.body),
                    spamScore = spamRepository.getScore(row.id) ?: -1f
                )
            }
        val mmsMessages = mmsRows
            .filter { contentFilter.includes(-it.mmsId in spamMessageIds) }
            .map { row ->
                row.toMessageModel(
                    parsedResult = parseMessage(row.address, row.textContent),
                    spamScore = spamRepository.getScore(-row.mmsId) ?: -1f
                )
            }

        emit(
            (smsMessages + mmsMessages)
                .sortedByDescending { it.timestamp }
                .drop(offset)
                .take(limit)
        )
    }.flowOn(Dispatchers.IO)

    override fun getMessages(): Flow<List<MessageModel>> = flow {
        val smsMessages = telephonyDataSource.getAllSmsMessages()
            .map { row ->
                row.toMessageModel(parsedResult = parseMessage(row.address, row.body))
            }
        val mmsMessages = telephonyDataSource.getAllMmsMessages()
            .map { row ->
                row.toMessageModel(parsedResult = parseMessage(row.address, row.textContent))
            }

        emit((smsMessages + mmsMessages).sortedByDescending { it.timestamp })
    }.flowOn(Dispatchers.IO)

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
        threadIds: List<Long>,
        contentFilter: ConversationContentFilter = ConversationContentFilter.ALL,
    ): List<ConversationModel> {
        val messagesMap = mutableMapOf<Long, ConversationModel>()
        val smsRows = threadIds.chunked(CONVERSATION_FILTER_CHUNK_SIZE)
            .flatMap(telephonyDataSource::fetchConversationSmsRows)
        val mmsRows = threadIds.chunked(CONVERSATION_FILTER_CHUNK_SIZE)
            .flatMap(telephonyDataSource::fetchConversationMmsRows)
        val spamMessageIds = if (contentFilter == ConversationContentFilter.ALL) {
            emptySet()
        } else {
            spamRepository.getSpamMessageIds(
                smsRows.map { it.id } + mmsRows.map { -it.mmsId },
                SPAM_THRESHOLD,
            )
        }

        smsRows
            .filter { contentFilter.includes(it.id in spamMessageIds) }
            .forEach { row -> messagesMap.mergeSmsConversation(row) }
        mmsRows
            .filter { contentFilter.includes(-it.mmsId in spamMessageIds) }
            .forEach { row -> messagesMap.mergeMmsConversation(row) }

        return threadIds.mapNotNull { messagesMap[it] }
    }

    private fun MutableMap<Long, ConversationModel>.mergeSmsConversation(row: SmsConversationRow) {
        val existing = this[row.threadId]
        if (existing == null) {
            this[row.threadId] = ConversationModel(
                threadId = row.threadId,
                address = row.address,
                displayName = contactDataSource.getDisplayName(row.address),
                snippet = row.body,
                timestamp = row.date,
                unreadCount = if (row.read) 0 else 1
            )
        } else if (!row.read) {
            this[row.threadId] = existing.copy(unreadCount = existing.unreadCount + 1)
        }
    }

    private fun MutableMap<Long, ConversationModel>.mergeMmsConversation(row: MmsConversationRow) {
        val existing = this[row.threadId]
        if (existing == null) {
            this[row.threadId] = ConversationModel(
                threadId = row.threadId,
                address = row.address,
                displayName = contactDataSource.getDisplayName(row.address),
                snippet = row.subject ?: row.textContent,
                timestamp = row.date,
                unreadCount = if (row.read) 0 else 1,
                isMms = true,
                hasMms = true
            )
            return
        }

        if (row.date > existing.timestamp) {
            val address = if (existing.address.isNotBlank()) existing.address else row.address
            this[row.threadId] = existing.copy(
                snippet = row.subject ?: row.textContent,
                timestamp = row.date,
                address = address,
                displayName = existing.displayName ?: contactDataSource.getDisplayName(address),
                unreadCount = existing.unreadCount + if (row.read) 0 else 1,
                isMms = true,
                hasMms = true
            )
        } else {
            this[row.threadId] = existing.copy(
                unreadCount = existing.unreadCount + if (row.read) 0 else 1,
                hasMms = true
            )
        }
    }

    private fun SmsMessageRow.toMessageModel(
        parsedResult: ParsedResult,
        spamScore: Float = -1f
    ): MessageModel {
        return MessageModel(
            id = id,
            threadId = threadId,
            sender = address,
            content = body,
            timestamp = date,
            subId = subId,
            simName = telephonyDataSource.getSimName(subId),
            isRead = read,
            isReceived = isReceived,
            parsedResult = parsedResult,
            spamScore = spamScore
        )
    }

    private fun MmsMessageRow.toMessageModel(
        parsedResult: ParsedResult,
        spamScore: Float = -1f
    ): MessageModel {
        return MessageModel(
            id = -mmsId,
            threadId = threadId,
            sender = address,
            content = textContent,
            timestamp = date,
            subId = subId,
            simName = telephonyDataSource.getSimName(subId),
            isRead = read,
            isReceived = isReceived,
            parsedResult = parsedResult,
            imageUris = imageUris,
            mmsSubject = subject,
            isMms = true,
            spamScore = spamScore
        )
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
