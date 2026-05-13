package vip.mystery0.pixel.text.domain.spam

interface SpamRepository {
    suspend fun getScore(messageId: Long): Float?
    suspend fun save(messageId: Long, threadId: Long, score: Float)
    fun isEnabled(): Boolean
}
