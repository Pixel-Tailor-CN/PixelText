package vip.mystery0.pixel.text.domain.spam

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey

interface SpamRepository {
    suspend fun getScore(messageId: Long): Float?
    suspend fun savePrediction(messageId: Long, threadId: Long, prediction: SpamPrediction, key: LearningMessageKey, allowDisabled: Boolean = false): Boolean
    suspend fun isPredictionCurrent(prediction: SpamPrediction): Boolean
    suspend fun scoreForKey(key: LearningMessageKey): Float?
    suspend fun scoresForKeys(keys: List<LearningMessageKey>): Map<Long, Float>
    suspend fun getIdentifiedMessageIds(messageIds: List<Long>): Set<Long>
    suspend fun getSpamMessageIds(messageIds: List<Long>, threshold: Float): Set<Long>
    /** 只缩小身份核验范围；返回值仍须校验当前指纹、模型版本与白名单。 */
    suspend fun getPotentialSpamMessageIds(threshold: Float): Set<Long>
    suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long>
    suspend fun delete(messageIds: Set<Long>)
    fun observeChanges(): Flow<Unit>
    fun isEnabled(): Boolean
}
