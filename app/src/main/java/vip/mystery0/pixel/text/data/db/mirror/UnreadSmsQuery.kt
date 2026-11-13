package vip.mystery0.pixel.text.data.db.mirror

import androidx.sqlite.db.SimpleSQLiteQuery
import vip.mystery0.pixel.text.domain.model.UnreadSmsCountFilter

/** Provider 提供实时成员关系，主库只补充当前分类和归档状态。 */
internal data class UnreadSmsCandidate(val messageId: Long, val threadId: Long)

internal object UnreadSmsQuery {
    // 每个候选只绑定消息 ID 和会话 ID，兼容 SQLite 的 999 参数上限。
    const val MAX_CANDIDATES = 450

    fun count(
        candidates: List<UnreadSmsCandidate>,
        filter: UnreadSmsCountFilter,
        fullySpamThreadIds: Set<Long>,
    ): SimpleSQLiteQuery {
        require(candidates.isNotEmpty() && candidates.size <= MAX_CANDIDATES)
        val values = candidates.joinToString(",") {
            "(?, ?, ${if (it.threadId in fullySpamThreadIds) 1 else 0})"
        }
        return SimpleSQLiteQuery("""
            WITH candidate(messageId, threadId, excludeThread) AS (VALUES $values)
            SELECT COUNT(*) FROM candidate c
            LEFT JOIN mirror_message m ON m.transport='SMS' AND m.sourceId=c.messageId
            LEFT JOIN archived_conversation a ON a.thread_id=c.threadId
            WHERE c.excludeThread=0 AND (
                (${filter.includeArchivedMessages.asSqlFlag()}=1 AND a.thread_id IS NOT NULL)
                OR (a.thread_id IS NULL AND (
                    (${filter.includeSpamMessages.asSqlFlag()}=1 AND m.isSpam=1)
                    OR (${filter.includeNormalMessages.asSqlFlag()}=1 AND COALESCE(m.isSpam, 0)!=1)
                ))
            )
        """.trimIndent(), arguments(candidates))
    }

    /** 非默认短信状态下只统计本地镜像中的收件箱未读短信，不访问系统 Provider。 */
    fun countFromMirror(filter: UnreadSmsCountFilter): SimpleSQLiteQuery {
        val excludeFullySpam = filter.excludeFullySpamConversations.asSqlFlag()
        val includeArchived = filter.includeArchivedMessages.asSqlFlag()
        val includeSpam = filter.includeSpamMessages.asSqlFlag()
        val includeNormal = filter.includeNormalMessages.asSqlFlag()
        return SimpleSQLiteQuery("""
            SELECT COUNT(*) FROM mirror_message m
            LEFT JOIN archived_conversation a ON a.thread_id=m.threadId
            WHERE m.transport='SMS' AND m.boxType=1 AND m.read=0
              AND (
                  $excludeFullySpam=0
                  OR a.thread_id IS NOT NULL
                  OR m.threadId IS NULL
                  OR EXISTS (
                      SELECT 1 FROM mirror_message m2
                      WHERE m2.threadId=m.threadId AND COALESCE(m2.isSpam, 0)!=1
                  )
              )
              AND (
                  ($includeArchived=1 AND a.thread_id IS NOT NULL)
                  OR (a.thread_id IS NULL AND (
                      ($includeSpam=1 AND m.isSpam=1)
                      OR ($includeNormal=1 AND COALESCE(m.isSpam, 0)!=1)
                  ))
              )
        """.trimIndent())
    }

    /** 一条正常、未识别或尚未镜像的原消息，就足以否定“全骚扰”；归档会话不参与排除。 */
    fun nonFullySpamThreads(candidates: List<UnreadSmsCandidate>): SimpleSQLiteQuery {
        require(candidates.isNotEmpty() && candidates.size <= MAX_CANDIDATES)
        val values = candidates.joinToString(",") { "(?, ?)" }
        return SimpleSQLiteQuery("""
            WITH candidate(messageId, threadId) AS (VALUES $values)
            SELECT DISTINCT c.threadId FROM candidate c
            LEFT JOIN mirror_message m
                ON m.transport=CASE WHEN c.messageId<0 THEN 'MMS' ELSE 'SMS' END
                AND m.sourceId=CASE WHEN c.messageId<0 THEN -c.messageId ELSE c.messageId END
            LEFT JOIN archived_conversation a ON a.thread_id=c.threadId
            WHERE a.thread_id IS NOT NULL OR COALESCE(m.isSpam, 0)!=1
        """.trimIndent(), arguments(candidates))
    }

    private fun arguments(candidates: List<UnreadSmsCandidate>): Array<Any?> =
        candidates.flatMap { listOf<Any?>(it.messageId, it.threadId) }.toTypedArray()

    private fun Boolean.asSqlFlag(): Int = if (this) 1 else 0
}
