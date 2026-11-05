package vip.mystery0.pixel.text.domain.model.mirror

import vip.mystery0.pixel.text.domain.repository.ConversationContentFilter

/** 本轮已核验的消息版本；消息发生变化后不能继续使用旧判定。 */
data class ConversationMessageVersion(val localId: Long, val revision: Long)

/** 会话聚合查询；分页发生在选定会话后、读取最新消息正文前。 */
data class ConversationListQuery(
    val contentFilter: ConversationContentFilter = ConversationContentFilter.ALL,
    val spamMessages: List<ConversationMessageVersion> = emptyList(),
    val threadIds: List<Long>? = null,
    val excludedThreadIds: Set<Long> = emptySet(),
    val onlySpamThreads: Boolean = false,
    val incomingUnreadOnly: Boolean = false,
    val limit: Int? = null,
    val offset: Int = 0,
)
