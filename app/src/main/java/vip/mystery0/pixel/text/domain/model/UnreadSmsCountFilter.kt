package vip.mystery0.pixel.text.domain.model

data class UnreadSmsCountFilter(
    val includeNormalMessages: Boolean,
    val includeSpamMessages: Boolean,
    val includeArchivedMessages: Boolean,
    val excludeFullySpamConversations: Boolean = false,
)
