package vip.mystery0.pixel.text.domain.model

data class BlockedKeyword(
    val id: Long,
    val keyword: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class KeywordSpamMessage(
    val messageId: Long,
    val threadId: Long,
    val content: String,
)

sealed interface KeywordSaveResult {
    data class Success(val keyword: BlockedKeyword) : KeywordSaveResult
    data object Empty : KeywordSaveResult
    data object Duplicate : KeywordSaveResult
    data object NotFound : KeywordSaveResult
}
