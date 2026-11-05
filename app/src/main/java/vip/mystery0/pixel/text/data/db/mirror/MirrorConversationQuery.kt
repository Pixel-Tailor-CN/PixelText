package vip.mystery0.pixel.text.data.db.mirror

import androidx.sqlite.db.SimpleSQLiteQuery
import vip.mystery0.pixel.text.domain.model.mirror.ConversationListQuery
import vip.mystery0.pixel.text.domain.repository.ConversationContentFilter

/** 只拼接类型明确的数值和固定 SQL，不将号码、正文或其他文本放入查询。 */
internal object MirrorConversationQuery {
    fun build(query: ConversationListQuery): SimpleSQLiteQuery {
        require(query.offset >= 0 && (query.limit == null || query.limit >= 0))
        val threadCondition = when {
            query.threadIds == null -> "m.threadId > 0"
            query.threadIds.isEmpty() -> "0"
            else -> "m.threadId IN (${query.threadIds.distinct().joinToString(",")})"
        }
        val excludedCondition = if (query.excludedThreadIds.isEmpty()) "" else
            "AND m.threadId NOT IN (${query.excludedThreadIds.joinToString(",")})"
        val baseCondition = "$threadCondition $excludedCondition"
        val needsSpam = query.onlySpamThreads || query.contentFilter != ConversationContentFilter.ALL
        val spamDefinition = if (!needsSpam) "" else """
            spam AS (
                SELECT m.localId, m.threadId, COALESCE(m.timestamp, 0) AS timestamp,
                    m.transport, m.read, m.boxType
                FROM mirror_message m
                WHERE m.isSpam=1 AND $baseCondition
            ),
        """.trimIndent()
        val pagination = "LIMIT ${query.limit ?: -1} OFFSET ${query.offset}"
        // 仅显示骚扰消息时，最终分组顺序就是选页顺序，无需先额外分组一次。
        val needsThreadPage = query.onlySpamThreads && query.contentFilter != ConversationContentFilter.SPAM
        val threadPageDefinition = if (!needsThreadPage) "" else """
            spam_ranked AS (
                SELECT threadId, timestamp, localId,
                    ROW_NUMBER() OVER (PARTITION BY threadId ORDER BY timestamp DESC, localId DESC) AS rowRank
                FROM spam
            ),
            thread_page AS (
                SELECT threadId FROM spam_ranked WHERE rowRank=1
                ORDER BY timestamp DESC, localId DESC $pagination
            ),
        """.trimIndent()
        val source = if (query.contentFilter == ConversationContentFilter.SPAM) "spam" else "mirror_message"
        // 最终状态随消息写入，读取列表只筛选标记，未识别消息仍然可见。
        val classificationCondition = if (query.contentFilter == ConversationContentFilter.NORMAL)
            "AND (m.isSpam=0 OR m.isSpam IS NULL)" else ""
        val pageThreadCondition = if (needsThreadPage)
            "AND m.threadId IN (SELECT threadId FROM thread_page)" else ""
        val unreadExpression = if (query.incomingUnreadOnly)
            "CASE WHEN read=0 AND boxType=1 THEN 1 ELSE 0 END" else
            "CASE WHEN read=1 THEN 0 ELSE 1 END"
        // 骚扰线程模式已按垃圾消息时间分页；这里只按实际显示的最新消息排序。
        val pagePagination = if (needsThreadPage) "LIMIT -1" else pagination
        return SimpleSQLiteQuery("""
            WITH $spamDefinition $threadPageDefinition
            visible AS (
                SELECT m.localId, m.threadId, COALESCE(m.timestamp, 0) AS timestamp,
                    m.transport, m.read, m.boxType
                FROM $source m
                WHERE $baseCondition $classificationCondition $pageThreadCondition
            ), ranked AS (
                SELECT localId, threadId, timestamp, transport,
                    ROW_NUMBER() OVER (PARTITION BY threadId ORDER BY timestamp DESC, localId DESC) AS rowRank,
                    SUM($unreadExpression) OVER (PARTITION BY threadId) AS unreadCount,
                    MAX(CASE WHEN transport='MMS' THEN 1 ELSE 0 END) OVER (PARTITION BY threadId) AS containsMms
                FROM visible
            ), page AS (
                SELECT localId, threadId, timestamp, transport, unreadCount, containsMms
                FROM ranked WHERE rowRank=1
                ORDER BY timestamp DESC, localId DESC $pagePagination
            )
            SELECT p.localId, p.threadId, p.timestamp, p.transport='MMS' AS latestIsMms,
                p.unreadCount, p.containsMms, s.address, s.body AS snippet
            FROM page p LEFT JOIN mirror_sms s ON s.localId=p.localId AND p.transport='SMS'
            ORDER BY p.timestamp DESC, p.localId DESC
        """.trimIndent())
    }
}
