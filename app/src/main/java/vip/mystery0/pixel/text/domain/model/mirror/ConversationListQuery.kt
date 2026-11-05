package vip.mystery0.pixel.text.domain.model.mirror

import vip.mystery0.pixel.text.domain.repository.ConversationContentFilter

/** 会话聚合查询；分页发生在选定会话后、读取最新消息正文前。 */
data class ConversationListQuery(
    val contentFilter: ConversationContentFilter = ConversationContentFilter.ALL,
    val threadIds: List<Long>? = null,
    val excludedThreadIds: Set<Long> = emptySet(),
    val archiveFilter: ConversationArchiveFilter = ConversationArchiveFilter.ALL,
    val onlySpamThreads: Boolean = false,
    val incomingUnreadOnly: Boolean = false,
    val limit: Int? = null,
    val offset: Int = 0,
)

enum class ConversationArchiveFilter { ALL, ARCHIVED, UNARCHIVED }
