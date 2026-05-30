package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.db.toArchivedConversationEntity
import vip.mystery0.pixel.text.data.db.toConversationModel
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
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.repository.MessageSearchFilter
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository

private const val SPAM_THRESHOLD = 0.7f
private const val FULLY_SPAM_FILTER_CHUNK_SIZE = 200

class MessageRepositoryImpl(
    private val telephonyDataSource: TelephonyDataSource,
    private val contactDataSource: ContactDataSource,
    private val messageParser: MessageParser,
    private val spamRepository: SpamRepository,
    private val settingsRepository: AppSettingsRepository,
    private val archiveDatabase: ConversationArchiveDatabase
) : MessageRepository {

    private val archiveDao = archiveDatabase.archivedConversationDao()

    override fun getConversations(limit: Int, offset: Int): Flow<List<ConversationModel>> = flow {
        val archivedThreadIds = archiveDao.getArchivedThreadIds().toSet()
        val threadIds = queryFilteredConversationThreadIds(
            limit = limit,
            offset = offset,
            archivedThreadIds = archivedThreadIds
        )
        if (threadIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        emit(fetchConversationDetails(threadIds).sortedByDescending { it.timestamp })
    }.flowOn(Dispatchers.IO)

    override fun getArchivedConversations(limit: Int, offset: Int): Flow<List<ConversationModel>> =
        flow {
            val conversations = archiveDao.getArchivedConversations(limit, offset)
                .map { it.toConversationModel() }
            if (conversations.isEmpty()) {
                emit(emptyList())
                return@flow
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
            emit(fetchConversationDetails(threadIds).sortedByDescending { it.timestamp })
        }.flowOn(Dispatchers.IO)

    override fun searchConversations(query: String): Flow<List<ConversationModel>> = flow {
        val threadIds = telephonyDataSource.searchConversationThreadIds(query)
        if (threadIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val sorted = fetchConversationDetails(threadIds)
            .sortedByDescending { it.timestamp }
        emit(sorted)
    }.flowOn(Dispatchers.IO)

    override suspend fun archiveConversations(conversations: List<ConversationModel>) {
        if (conversations.isEmpty()) return
        withContext(Dispatchers.IO) {
            val archivedAt = System.currentTimeMillis()
            archiveDao.archive(conversations.map { it.toArchivedConversationEntity(archivedAt) })
        }
    }

    override suspend fun unarchiveThreads(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            archiveDao.unarchive(threadIds)
        }
    }

    override suspend fun deleteThreads(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            telephonyDataSource.deleteThreads(threadIds)
            archiveDao.unarchive(threadIds)
        }
    }

    override suspend fun deleteMessages(messageIds: Set<Long>): Int {
        if (messageIds.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val deletedCount = telephonyDataSource.deleteMessages(messageIds)
            spamRepository.delete(messageIds)
            deletedCount
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

        emit((smsMessages + mmsMessages).sortedByDescending { it.timestamp })
    }.flowOn(Dispatchers.IO)

    override fun getMessagesByThread(
        threadId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<MessageModel>> = flow {
        val totalNeeded = limit + offset
        val smsMessages = telephonyDataSource.getSmsMessagesByThread(threadId, totalNeeded)
            .map { row ->
                row.toMessageModel(
                    parsedResult = parseMessage(row.address, row.body),
                    spamScore = spamRepository.getScore(row.id) ?: -1f
                )
            }
        val mmsMessages = telephonyDataSource.getMmsMessagesByThread(threadId, totalNeeded)
            .map { row ->
                row.toMessageModel(
                    parsedResult = parseMessage(row.address, row.textContent),
                    spamScore = spamRepository.getScore(-row.mmsId) ?: -1f
                )
            }

        val merged = (smsMessages + mmsMessages)
            .sortedByDescending { it.timestamp }
            .drop(offset)
            .take(limit)
        emit(merged)
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
        withContext(Dispatchers.IO) {
            telephonyDataSource.markThreadAsRead(threadId)
        }
    }

    private suspend fun queryFilteredConversationThreadIds(
        limit: Int,
        offset: Int,
        archivedThreadIds: Set<Long>
    ): List<Long> {
        val threadIds = telephonyDataSource.queryConversationThreadIds()
            .distinct()
            .filter { threadId -> threadId !in archivedThreadIds }

        if (!settingsRepository.isHideFullySpamConversationsEnabled()) {
            return threadIds
                .drop(offset)
                .take(limit)
        }

        val targetCount = offset + limit
        val visibleThreadIds = mutableListOf<Long>()
        threadIds.chunked(FULLY_SPAM_FILTER_CHUNK_SIZE).forEach { chunk ->
            val fullySpamThreadIds = findFullySpamThreadIds(chunk)
            visibleThreadIds += chunk.filterNot { it in fullySpamThreadIds }
            if (visibleThreadIds.size >= targetCount) {
                return visibleThreadIds.drop(offset).take(limit)
            }
        }

        return visibleThreadIds.drop(offset).take(limit)
    }

    private suspend fun findFullySpamThreadIds(threadIds: List<Long>): Set<Long> {
        if (threadIds.isEmpty()) return emptySet()

        val messageIdsByThread = telephonyDataSource.getConversationMessageIdsByThread(threadIds)
        val allMessageIds = messageIdsByThread.values.flatten()
        if (allMessageIds.isEmpty()) return emptySet()

        val spamMessageIds = spamRepository.getSpamMessageIds(allMessageIds, SPAM_THRESHOLD)
        return messageIdsByThread
            .filterValues { messageIds ->
                messageIds.isNotEmpty() && messageIds.all { it in spamMessageIds }
            }
            .keys
    }

    private fun fetchConversationDetails(threadIds: List<Long>): List<ConversationModel> {
        val messagesMap = mutableMapOf<Long, ConversationModel>()

        telephonyDataSource.fetchConversationSmsRows(threadIds).forEach { row ->
            messagesMap.mergeSmsConversation(row)
        }

        telephonyDataSource.fetchConversationMmsRows(threadIds).forEach { row ->
            messagesMap.mergeMmsConversation(row)
        }

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

    private fun parseMessage(address: String, content: String): ParsedResult {
        if (!settingsRepository.isSmartCardEnabled()) return ParsedResult.None
        return messageParser.parse(address, content)
    }
}
