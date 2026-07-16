package vip.mystery0.pixel.text.domain.model

data class VerificationCodeIndexModel(
    val messageId: Long,
    val threadId: Long,
    val address: String,
    val timestamp: Long,
    val monthKey: String,
    val code: String,
    val signature: String?,
    val ruleVersion: String,
    val displayName: String? = null,
)

data class VerificationCodeMonthModel(
    val monthKey: String,
    val latestTimestamp: Long,
    val messageCount: Int,
)
