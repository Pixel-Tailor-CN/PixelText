package vip.mystery0.pixel.text.domain.spam

interface SpamRepository {
    suspend fun getScore(messageId: Long): Float?
    suspend fun save(messageId: Long, threadId: Long, score: Float)
    suspend fun getIdentifiedMessageIds(messageIds: List<Long>): Set<Long>
    suspend fun getSpamMessageIds(messageIds: List<Long>, threshold: Float): Set<Long>
    suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long>
    suspend fun delete(messageIds: Set<Long>)
    fun isEnabled(): Boolean
}
