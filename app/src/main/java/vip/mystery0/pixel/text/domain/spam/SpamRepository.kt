package vip.mystery0.pixel.text.domain.spam

interface SpamRepository {
    suspend fun getScore(messageId: Long): Float?
    suspend fun save(messageId: Long, threadId: Long, score: Float)
    suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long>
    fun isEnabled(): Boolean
}
