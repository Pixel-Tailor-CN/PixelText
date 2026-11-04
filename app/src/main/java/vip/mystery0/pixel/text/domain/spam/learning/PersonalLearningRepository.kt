package vip.mystery0.pixel.text.domain.spam.learning

import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

enum class LearningLabel { NORMAL, SPAM }
enum class LearningStatus { IDLE, QUEUED, RUNNING, APPLIED, FAILED }
data class LearningMessageKey(val sourceType: String, val sourceId: Long, val fingerprint: String) {
    companion object {
        fun create(sourceType: String, sourceId: Long, sender: String, date: Long, text: String): LearningMessageKey {
            val parts = listOf(sourceType, sourceId.toString(), sender, date.toString(), text)
            val identity = parts.joinToString("") { "${it.length}:$it" }
            return LearningMessageKey(sourceType, sourceId, learningDigest(identity))
        }
    }
}
fun learningDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
data class LearningSample(val id: String, val text: String, val label: LearningLabel,
    val updatedAt: Long, val revision: Long, val linkedMessageCount: Int = 0)
data class LearningSnapshot(val revision: Long, val samples: List<LearningSample>)
data class LearningState(val revision: Long = 0, val status: LearningStatus = LearningStatus.IDLE,
    val sampleCount: Int = 0, val error: String? = null)
data class PersonalParameters(val identity: String, val generation: Long, val payload: FloatArray)

interface PersonalLearningRepository {
    fun observeSamples(): Flow<List<LearningSample>>
    fun observeState(): Flow<LearningState>
    suspend fun record(key: LearningMessageKey, text: String, label: LearningLabel): Long
    suspend fun deleteSample(id: String)
    suspend fun clear()
    suspend fun linkedMessageKeys(): List<LearningMessageKey>
    suspend fun unlinkIds(sourceType: String, ids: List<Long>)
    suspend fun forgetDeletedMessages(keys: List<LearningMessageKey>)
    suspend fun unlinkMessage(key: LearningMessageKey)
    suspend fun correction(key: LearningMessageKey): LearningLabel?
    suspend fun snapshot(): LearningSnapshot
    suspend fun stateRevision(): Long
    suspend fun currentParameters(identity: String): PersonalParameters?
    suspend fun <T> withDecision(block: suspend () -> T): T
    // 以下操作由协调器在 withDecision 内调用，样本代次仍在数据库内复核。
    suspend fun invalidatePersonalParameters(): Long
    suspend fun updateStatus(revision: Long, status: LearningStatus, error: String? = null)
    suspend fun commitParameters(revision: Long, parameters: PersonalParameters): Boolean
}
