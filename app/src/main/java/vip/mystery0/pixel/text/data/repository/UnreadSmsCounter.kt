package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.UnreadSmsCandidate
import vip.mystery0.pixel.text.data.db.mirror.UnreadSmsQuery
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.UnreadSmsCountFilter
import vip.mystery0.pixel.text.domain.spam.SpamRepository

class UnreadSmsCounter(
    private val telephonyDataSource: TelephonyDataSource,
    private val spamRepository: SpamRepository,
    database: MessageMirrorDatabase,
) {
    private val archiveDao = database.archivedConversationDao()
    private val mirrorDao = database.mirrorDao()

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

        // 初始化可能获取同步锁，必须在主库查询或事务之前完成。
        spamRepository.ensureReady()
        val fullySpamThreadIds = if (filter.excludeFullySpamConversations) {
            findFullySpamThreadIds(unreadMessages.map { it.threadId }.distinct())
        } else {
            emptySet()
        }

        return unreadMessages.chunked(UnreadSmsQuery.MAX_CANDIDATES).sumOf { batch ->
            mirrorDao.filteredUnreadSmsCount(UnreadSmsQuery.count(
                batch.map { UnreadSmsCandidate(it.id, it.threadId) },
                filter,
                fullySpamThreadIds,
            ))
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
                // 不能仅按镜像判断全会话：尚未同步的正常短信也必须阻止整组隐藏。
                val candidates = messageIdsByThread.flatMap { (threadId, messageIds) ->
                    messageIds.map { UnreadSmsCandidate(it, threadId) }
                }
                val nonFullySpam = candidates.chunked(UnreadSmsQuery.MAX_CANDIDATES)
                    .flatMap { batch -> mirrorDao.nonFullySpamThreadIds(UnreadSmsQuery.nonFullySpamThreads(batch)) }
                    .toSet()
                messageIdsByThread.filterValues { it.isNotEmpty() }.keys - nonFullySpam
            }
            .toSet()
    }

    private companion object {
        const val FULLY_SPAM_FILTER_CHUNK_SIZE = 200
    }
}
