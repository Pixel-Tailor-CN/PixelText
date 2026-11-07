package vip.mystery0.pixel.text.domain.spam

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey

interface SpamRepository {
    /** 一次性迁移与中断写入恢复；正常列表查询不重新判定。 */
    suspend fun ensureReady()
    suspend fun getScore(messageId: Long): Float?
    suspend fun getScores(messageIds: List<Long>): Map<Long, Float>
    suspend fun savePrediction(messageId: Long, threadId: Long, prediction: SpamPrediction, key: LearningMessageKey, allowDisabled: Boolean = false): Boolean
    suspend fun isPredictionCurrent(prediction: SpamPrediction): Boolean
    suspend fun scoreForKey(key: LearningMessageKey): Float?
    suspend fun scoresForKeys(keys: List<LearningMessageKey>): Map<Long, Float>
    suspend fun getIdentifiedMessageIds(messageIds: List<Long>): Set<Long>
    /** 后台重扫跳过当前模型已处理的消息；不影响列表中已存的最终状态。 */
    suspend fun getCurrentModelMessageIds(messageIds: List<Long>): Set<Long>
    suspend fun getSpamMessageIds(messageIds: List<Long>, threshold: Float): Set<Long>
    suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long>
    suspend fun delete(messageIds: Set<Long>)
    fun observeChanges(): Flow<Unit>
    fun isEnabled(): Boolean
}
