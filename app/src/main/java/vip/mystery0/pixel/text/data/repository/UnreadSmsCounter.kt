package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.UnreadSmsCountFilter
import vip.mystery0.pixel.text.domain.spam.SpamRepository

class UnreadSmsCounter(
    private val telephonyDataSource: TelephonyDataSource,
    private val spamRepository: SpamRepository,
    archiveDatabase: ConversationArchiveDatabase,
) {
    private val archiveDao = archiveDatabase.archivedConversationDao()

    suspend fun count(filter: UnreadSmsCountFilter): Int {
        if (!filter.includeNormalMessages &&
            !filter.includeSpamMessages &&
            !filter.includeArchivedMessages
        ) {
            return 0
        }

        val unreadMessages = telephonyDataSource.getUnreadSmsMessagesForSmartspacer()
        if (unreadMessages.isEmpty()) return 0
        if (filter.includeNormalMessages &&
            filter.includeSpamMessages &&
            filter.includeArchivedMessages &&
            !filter.excludeFullySpamConversations
        ) {
            return unreadMessages.size
        }

        val archivedThreadIds = archiveDao.getArchivedThreadIds().toSet()
        val unreadSpamMessageIds = spamRepository.getSpamMessageIds(
            unreadMessages.map { it.id },
            SPAM_THRESHOLD,
        )
        val fullySpamThreadIds = if (filter.excludeFullySpamConversations) {
            findFullySpamThreadIds(
                unreadMessages.map { it.threadId }
                    .distinct()
                    .filterNot { it in archivedThreadIds },
            )
        } else {
            emptySet()
        }

        return unreadMessages.count { row ->
            if (row.threadId in fullySpamThreadIds) return@count false
            val isArchived = row.threadId in archivedThreadIds
            val isSpam = row.id in unreadSpamMessageIds
            val isNormal = !isArchived && !isSpam
            (filter.includeArchivedMessages && isArchived) ||
                (filter.includeSpamMessages && !isArchived && isSpam) ||
                (filter.includeNormalMessages && isNormal)
        }
    }

    fun observeClassificationChanges(): Flow<Unit> = merge(
        archiveDao.observeArchivedThreadIds().map { },
        spamRepository.observeChanges(),
    )

    private suspend fun findFullySpamThreadIds(threadIds: List<Long>): Set<Long> {
        if (threadIds.isEmpty()) return emptySet()
        return threadIds.chunked(FULLY_SPAM_FILTER_CHUNK_SIZE)
            .flatMap { chunk ->
                val messageIdsByThread =
                    telephonyDataSource.getConversationMessageIdsByThread(chunk)
                val allMessageIds = messageIdsByThread.values.flatten()
                val spamMessageIds = spamRepository.getSpamMessageIds(
                    allMessageIds,
                    SPAM_THRESHOLD,
                )
                messageIdsByThread.filterValues { messageIds ->
                    messageIds.isNotEmpty() && messageIds.all { it in spamMessageIds }
                }.keys
            }
            .toSet()
    }

    private companion object {
        const val SPAM_THRESHOLD = 0.7f
        const val FULLY_SPAM_FILTER_CHUNK_SIZE = 200
    }
}
