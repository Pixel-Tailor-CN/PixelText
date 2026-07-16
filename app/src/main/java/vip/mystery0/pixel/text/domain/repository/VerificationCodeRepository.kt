package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.VerificationCodeIndexModel
import vip.mystery0.pixel.text.domain.model.VerificationCodeMonthModel

interface VerificationCodeRepository {
    fun observeMonths(): Flow<List<VerificationCodeMonthModel>>

    fun observeMonth(monthKey: String): Flow<List<VerificationCodeIndexModel>>

    suspend fun getMessageBody(messageId: Long): String?

    suspend fun indexMessage(
        messageId: Long,
        threadId: Long,
        address: String,
        body: String,
        timestamp: Long,
    )

    suspend fun rebuildAll()

    suspend fun reconcile()

    suspend fun getExpiredMessageIds(cutoffTimestamp: Long): List<Long>

    suspend fun deleteMessageIds(messageIds: Collection<Long>)

    suspend fun deleteThreadIds(threadIds: Collection<Long>)
}
