package vip.mystery0.pixel.text.data.repository

import androidx.room.withTransaction
import vip.mystery0.pixel.text.data.spam.learning.ModelContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.db.*
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamLegacyImporter
import vip.mystery0.pixel.text.data.db.mirror.MirrorSyncStateEntity
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock
import vip.mystery0.pixel.text.data.source.LearningMessageSource
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.spam.learning.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class PersonalLearningRepositoryImpl(
    private val database: PersonalLearningDatabase,
    private val mirror: MessageMirrorDatabase,
    private val importer: MirrorSpamLegacyImporter,
    private val messageSource: LearningMessageSource,
    private val synchronizer: MessageMirrorSynchronizer,
    private val refreshKeywords: suspend (List<Long>) -> Unit,
) : PersonalLearningRepository {
    private val dao = database.dao()
    private val decisions = mirror.spamDao()
    private val gate = Mutex()
    private var correctionsChecked = false
    override suspend fun <T> withDecision(block: suspend () -> T): T = gate.withLock { block() }
    override fun observeSamples(): Flow<List<LearningSample>> = dao.observeSamples().map { rows ->
        rows.map { it.sample.toModel(it.linkedMessageCount) }
    }
    override fun observeState(): Flow<LearningState> = combine(
        dao.observeState(),
        dao.observeSamples(),
        dao.observeParameters(),
    ) { state, samples, _ ->
        // 参数有效性需结合当前模型契约身份与参数维度校验，仓库层默认不直接声称有效。
        LearningState(
            revision = state?.revision ?: 0L,
            status = LearningStatus.valueOf(state?.status ?: "IDLE"),
            sampleCount = samples.size,
            error = state?.error,
            hasActiveParameters = false,
        )
    }
    private suspend fun <T> withImportedDecision(block: suspend () -> T): T {
        importer.ensureImported()
        return withDecision(block)
    }

    override suspend fun record(key: LearningMessageKey, text: String, label: LearningLabel): Long = withImportedDecision {
        require(text.isNotBlank()) { "empty learning sample" }
        markCorrectionsDirty()
        val result = database.withTransaction {
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
        reconcileMessageCorrectionsLocked()
        result
    }
    override suspend fun updateSampleLabel(id: String, label: LearningLabel): Boolean = withImportedDecision {
        markCorrectionsDirty()
        val result = database.withTransaction {
            val old = dao.samples().firstOrNull { it.id == id } ?: return@withTransaction false
            if (old.label == label.name) return@withTransaction false
            val revision = advanceRevisionKeepingParameters()
            dao.putSample(old.copy(label = label.name, updatedAt = System.currentTimeMillis(), revision = revision))
            true
        }
        reconcileMessageCorrectionsLocked()
        result
    }
    override suspend fun deleteSample(id: String) = withImportedDecision {
        markCorrectionsDirty()
        database.withTransaction { dao.deleteLinks(id); dao.deleteSample(id); invalidatePersonalParameters(); Unit }
        reconcileMessageCorrectionsLocked()
    }
    override suspend fun clear() = withImportedDecision {
        markCorrectionsDirty()
        database.withTransaction { dao.clearLinks(); dao.clearSamples(); invalidatePersonalParameters(); Unit }
        reconcileMessageCorrectionsLocked()
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

    /** 只修复跨库写入被进程终止打断的情况，干净状态不在开页时重新分类。 */
    suspend fun reconcileMessageCorrectionsIfNeeded() = withDecision {
        if (correctionsChecked) return@withDecision
        if (mirror.syncDao().state(CORRECTION_STATE)?.complete == false) {
            reconcileMessageCorrectionsLocked()
        }
        correctionsChecked = true
    }

    private suspend fun markCorrectionsDirty() {
        correctionsChecked = false
        mirror.syncDao().putState(MirrorSyncStateEntity(CORRECTION_STATE, complete = false))
    }

    private suspend fun reconcileMessageCorrectionsLocked() {
        val (links, labels) = database.withTransaction {
            dao.links() to dao.samples().associate { it.id to (it.label == LearningLabel.SPAM.name) }
        }
        val ids = links.map { it.sourceId }.distinct()
        // 修正可能来自尚未同步的消息；正文身份只在写入时核对。
        for (id in ids) {
            if (id == 0L || id == Long.MIN_VALUE) continue
            val key = SourceMessageKey(if (id > 0) MessageTransport.SMS else MessageTransport.MMS, kotlin.math.abs(id))
            check(synchronizer.refreshInitializationMessage(key)) { "message synchronization incomplete" }
        }
        // 关键词优先于人工纠正，不能让新消息先落入纠正结果后跳过规则匹配。
        if (ids.isNotEmpty()) refreshKeywords(ids)
        MirrorSynchronizationLock.mutex.withLock {
            val versions = decisions.allRows().associate { it.messageId to (it.localId to it.messageRevision) }
            val current = messageSource.keys(ids)
            val corrections = links.mapNotNull { link ->
                val expected = LearningMessageKey(link.sourceType, link.sourceId, link.fingerprint)
                if (current[link.sourceId] != expected) return@mapNotNull null
                labels[link.sampleId]?.let { link.sourceId to it }
            }.toMap()
            mirror.withTransaction {
                decisions.replaceCorrections(corrections, versions)
                mirror.syncDao().putState(MirrorSyncStateEntity(CORRECTION_STATE, complete = true))
            }
        }
        correctionsChecked = true
    }
    override suspend fun correction(key: LearningMessageKey): LearningLabel? =
        dao.correction(key.sourceType, key.sourceId, key.fingerprint)?.let(LearningLabel::valueOf)
    override suspend fun correctedMessageIds(label: LearningLabel): Set<Long> =
        dao.correctedMessageIds(label.name).toSet()
    override suspend fun corrections(keys: Collection<LearningMessageKey>): Map<LearningMessageKey, LearningLabel> {
        if (keys.isEmpty()) return emptyMap()
        val requested = keys.toSet()
        return buildMap {
            // 按来源分批查询，仍以完整指纹匹配，避免系统复用短信 ID 后误用旧纠正。
            requested.groupBy { it.sourceType }.forEach { (type, typedKeys) ->
                typedKeys.map { it.sourceId }.distinct().chunked(500).forEach { ids ->
                    dao.corrections(type, ids).forEach { row ->
                        val key = LearningMessageKey(row.sourceType, row.sourceId, row.fingerprint)
                        if (key in requested) put(key, LearningLabel.valueOf(row.label))
                    }
                }
            }
        }
    }
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

    private companion object {
        const val CORRECTION_STATE = "learning_decision_projection"
    }
}
