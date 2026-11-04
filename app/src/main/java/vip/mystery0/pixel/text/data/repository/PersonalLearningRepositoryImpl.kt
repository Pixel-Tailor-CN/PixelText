package vip.mystery0.pixel.text.data.repository

import androidx.room.withTransaction
import vip.mystery0.pixel.text.data.spam.learning.ModelContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.db.*
import vip.mystery0.pixel.text.domain.spam.learning.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class PersonalLearningRepositoryImpl(private val database: PersonalLearningDatabase) : PersonalLearningRepository {
    private val dao = database.dao()
    private val gate = Mutex()
    override suspend fun <T> withDecision(block: suspend () -> T): T = gate.withLock { block() }
    override fun observeSamples(): Flow<List<LearningSample>> = dao.observeSamples().map { rows ->
        rows.map { it.sample.toModel(it.linkedMessageCount) }
    }
    override fun observeState(): Flow<LearningState> = combine(dao.observeState(), dao.observeSamples()) { state, samples ->
        LearningState(state?.revision ?: 0, LearningStatus.valueOf(state?.status ?: "IDLE"), samples.size, state?.error)
    }
    override suspend fun record(key: LearningMessageKey, text: String, label: LearningLabel): Long = withDecision {
        require(text.isNotBlank()) { "empty learning sample" }
        database.withTransaction {
            // 新样本或改标签先推进样本代次，旧参数保留到新候选验证通过。
            val revision = advanceRevisionKeepingParameters()
            val normalized = ModelContract.normalize(text)
            val digest = learningDigest(normalized)
            // 摘要仅用于索引，按公开预处理归一后的正文一致才共享样本。
            val old = dao.byDigest(digest).firstOrNull { ModelContract.normalize(it.text) == normalized }
            val sample = LearningSampleEntity(old?.id ?: UUID.randomUUID().toString(), digest, text,
                label.name, System.currentTimeMillis(), revision)
            dao.putSample(sample)
            dao.putLink(LearningLinkEntity(key.sourceType, key.sourceId, key.fingerprint, sample.id))
            revision
        }
    }
    override suspend fun deleteSample(id: String) = withDecision {
        database.withTransaction { dao.deleteLinks(id); dao.deleteSample(id); invalidatePersonalParameters(); Unit }
    }
    override suspend fun clear() = withDecision {
        database.withTransaction { dao.clearLinks(); dao.clearSamples(); invalidatePersonalParameters(); Unit }
    }
    override suspend fun linkedMessageKeys(): List<LearningMessageKey> = dao.links().map {
        LearningMessageKey(it.sourceType, it.sourceId, it.fingerprint)
    }
    // 删除链路可能已持有决策锁，这里仅执行数据库事务，避免重入同一 Mutex。
    override suspend fun unlinkIds(sourceType: String, ids: List<Long>) = database.withTransaction {
        ids.chunked(500).forEach { dao.unlinkIds(sourceType, it) }
    }
    override suspend fun forgetDeletedMessages(keys: List<LearningMessageKey>) = database.withTransaction {
        keys.forEach { dao.unlink(it.sourceType, it.sourceId, it.fingerprint) }
    }
    override suspend fun unlinkMessage(key: LearningMessageKey) {
        dao.unlink(key.sourceType, key.sourceId, key.fingerprint)
    }
    override suspend fun correction(key: LearningMessageKey): LearningLabel? =
        dao.correction(key.sourceType, key.sourceId, key.fingerprint)?.let(LearningLabel::valueOf)
    override suspend fun snapshot(): LearningSnapshot = database.withTransaction {
        LearningSnapshot(stateRevision(), dao.samples().map { it.toModel() })
    }
    override suspend fun stateRevision(): Long = dao.state()?.revision ?: 0L
    private suspend fun advanceRevisionKeepingParameters(): Long = database.withTransaction {
        val revision = stateRevision() + 1
        dao.putState(LearningStateEntity(revision = revision))
        revision
    }
    override suspend fun invalidatePersonalParameters(): Long = database.withTransaction {
        val revision = stateRevision() + 1
        dao.clearParameters()
        dao.putState(LearningStateEntity(revision = revision))
        revision
    }
    override suspend fun updateStatus(revision: Long, status: LearningStatus, error: String?) = database.withTransaction {
        val state = dao.state() ?: LearningStateEntity()
        if (state.revision == revision) dao.putState(state.copy(status = status.name, error = error))
    }
    override suspend fun currentParameters(identity: String): PersonalParameters? = database.withTransaction {
        val state = dao.state() ?: return@withTransaction null
        val stored = dao.parameters(identity) ?: return@withTransaction null
        if (stored.revision > state.revision) return@withTransaction null
        val bytes = stored.payload
        if (bytes.isEmpty() || bytes.size % 4 != 0) return@withTransaction null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val payload = FloatArray(bytes.size / 4) { buffer.float }
        if (payload.any { !it.isFinite() }) return@withTransaction null
        PersonalParameters(identity, stored.revision, payload)
    }
    override suspend fun commitParameters(revision: Long, parameters: PersonalParameters): Boolean = database.withTransaction {
        val state = dao.state() ?: return@withTransaction false
        if (state.revision != revision || parameters.generation != revision || parameters.payload.isEmpty() || parameters.payload.any { !it.isFinite() }) return@withTransaction false
        val buffer = ByteBuffer.allocate(parameters.payload.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        parameters.payload.forEach { buffer.putFloat(it) }
        dao.putParameters(LearningParametersEntity(parameters.identity, revision, buffer.array()))
        dao.putState(state.copy(status = "APPLIED", error = null))
        true
    }
    private fun LearningSampleEntity.toModel(count: Int = 0) = LearningSample(id, text,
        LearningLabel.valueOf(label), updatedAt, revision, count)
}
