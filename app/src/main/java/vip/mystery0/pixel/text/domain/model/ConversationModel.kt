package vip.mystery0.pixel.text.domain.model

data class ConversationModel(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val timestamp: Long,
    val unreadCount: Int = 0,
    val isMms: Boolean = false
)
