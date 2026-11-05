package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.db.SpamResultEntity
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.source.LearningMessageSource
import vip.mystery0.pixel.text.data.spam.learning.ModelContract
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.*
import vip.mystery0.pixel.text.domain.spam.learning.*

class SpamRepositoryImpl(db: SpamDatabase,
    private val settingsRepository: AppSettingsRepository,
    private val whitelist: SenderWhitelistRepository,
    private val learning: PersonalLearningRepository,
    private val resources: HubResourceStore,
    private val messages: LearningMessageSource,
) : SpamRepository {
    private val dao = db.spamResultDao()
    private val keywordDao = db.blockedKeywordDao()
    private fun identity() = ModelContract.parse(resources.currentModelBundle().contractFile.readText()).identity
    private suspend fun generation(identity: String) = if (settingsRepository.settings.value.personalLearningEnabled) learning.currentParameters(identity)?.generation else null

    override suspend fun isPredictionCurrent(prediction: SpamPrediction): Boolean {
        val identity = identity()
        return prediction.modelIdentity == identity && prediction.revision == learning.stateRevision() && prediction.personalGeneration == generation(identity)
    }

    override suspend fun savePrediction(messageId: Long, threadId: Long, prediction: SpamPrediction, key: LearningMessageKey, allowDisabled: Boolean): Boolean = learning.withDecision {
        if ((!isEnabled() && !allowDisabled) || key.sourceId != messageId || messages.keys(listOf(messageId))[messageId] != key) return@withDecision false
        if (!prediction.score.isFinite() || prediction.score !in 0f..1f || !isPredictionCurrent(prediction)) return@withDecision false
        whitelist.withMessageDecision(messageId) { allowed ->
            if (!allowed) dao.insert(SpamResultEntity(messageId, threadId, prediction.score, System.currentTimeMillis(),
                if (allowDisabled) "manual_${prediction.source}" else prediction.source, prediction.modelIdentity, prediction.personalGeneration, key.fingerprint, prediction.revision))
        }
        true
    }

    private data class ScoreContext(
        val identity: String,
        val revision: Long,
        val generation: Long?,
        val enabled: Boolean,
    )

    private suspend fun scoreContext(): ScoreContext {
        val started = android.os.SystemClock.elapsedRealtime()
        val identity = identity()
        val context = ScoreContext(identity, learning.stateRevision(), generation(identity), isEnabled())
        if (vip.mystery0.pixel.text.BuildConfig.DEBUG) android.util.Log.d(
            "SpamScores", "score context loaded context_ms=${android.os.SystemClock.elapsedRealtime() - started}",
        )
        return context
    }

    private suspend fun scores(
        ids: List<Long>,
        keys: Map<Long, LearningMessageKey>,
        context: ScoreContext? = null,
    ): Map<Long, Float> {
        if (ids.isEmpty()) return emptyMap()
        val decision = context ?: scoreContext()
        val started = android.os.SystemClock.elapsedRealtime()
        val results = dao.results(ids).associateBy { it.messageId }
        val keywords = dao.keywordIds(ids).toSet()
        val corrections = learning.corrections(keys.filterKeys { it !in keywords }.values)
        val scores = buildMap {
            for (id in ids) {
                currentCoroutineContext().ensureActive()
                if (id in keywords) { put(id, 1f); continue }
                val corrected = keys[id]?.let { corrections[it] }
                if (corrected != null) { put(id, if (corrected == LearningLabel.SPAM) 1f else 0f); continue }
                val cached = results[id] ?: continue
                if (keys[id]?.fingerprint == cached.messageFingerprint && (decision.enabled || cached.source.startsWith("manual_")) && cached.modelIdentity == decision.identity && cached.decisionRevision == decision.revision && cached.personalGeneration == decision.generation && cached.source in setOf("public", "personal", "manual_public", "manual_personal")) put(id, cached.spamScore)
            }
        }
        if (vip.mystery0.pixel.text.BuildConfig.DEBUG) android.util.Log.d(
            "SpamScores", "score batch loaded count=${ids.size} batch_ms=${android.os.SystemClock.elapsedRealtime() - started}",
        )
        return scores
    }

    override suspend fun scoreForKey(key: LearningMessageKey): Float? = withContext(Dispatchers.IO) {
        scores(listOf(key.sourceId), mapOf(key.sourceId to key))[key.sourceId]
    }

    override suspend fun scoresForKeys(keys: List<LearningMessageKey>): Map<Long, Float> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyMap()
        // 一次完整读取共用决策版本，模型契约和个人参数只读取一次。
        val context = scoreContext()
        buildMap { keys.chunked(500).forEach { batch ->
            currentCoroutineContext().ensureActive()
            putAll(scores(batch.map { it.sourceId }, batch.associateBy { it.sourceId }, context))
        } }
    }

    override suspend fun getScore(messageId: Long): Float? = withContext(Dispatchers.IO) {
        if (whitelist.isAllowed(messageId)) 0f else scores(listOf(messageId), messages.keys(listOf(messageId)))[messageId]
    }

    override suspend fun getIdentifiedMessageIds(messageIds: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext emptySet()
        val context = scoreContext()
        buildSet {
            messageIds.chunked(500).forEach { ids ->
                currentCoroutineContext().ensureActive()
                addAll(scores(ids, messages.keys(ids), context).keys)
                addAll(whitelist.allowedMessageIds(ids))
            }
        }
    }

    override suspend fun getSpamMessageIds(messageIds: List<Long>, threshold: Float): Set<Long> = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext emptySet()
        val context = scoreContext()
        buildSet {
            messageIds.chunked(500).forEach { ids ->
                currentCoroutineContext().ensureActive()
                val candidates = scores(ids, messages.keys(ids), context).filterValues { it >= threshold }.keys
                addAll(candidates - whitelist.allowedMessageIds(candidates.toList()))
            }
        }
    }

    override suspend fun getPotentialSpamMessageIds(threshold: Float): Set<Long> = withContext(Dispatchers.IO) {
        // 候选可包含过期身份或已放行记录，最终仍由完整决策链核验，不能直接用于隐藏。
        buildSet {
            addAll(dao.getCandidateSpamMessageIds(threshold))
            addAll(learning.correctedMessageIds(LearningLabel.SPAM))
            // 常规隔离阈值大于零；零阈值时正常纠正的 0 分同样符合比较条件。
            if (threshold <= 0f) addAll(learning.correctedMessageIds(LearningLabel.NORMAL))
        }
    }

    override suspend fun getSpamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long> = withContext(Dispatchers.IO) {
        val ids = (dao.allDecisionIds() + learning.linkedMessageKeys().filter { it.sourceType == "SMS" }.map { it.sourceId }).distinct()
        messages.threadIdsByRecent(getSpamMessageIds(ids, threshold)).drop(offset).take(limit)
    }

    override suspend fun delete(messageIds: Set<Long>) {
        if (messageIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            messageIds.chunked(500).forEach {
                dao.deleteByMessageIds(it)
                keywordDao.deleteMatches(it)
                whitelist.forget(it)
                val current = messages.keys(it)
                learning.linkedMessageKeys().filter { key -> key.sourceType == "SMS" && key.sourceId in it && current[key.sourceId] != key }
                    .forEach { key -> learning.unlinkMessage(key) }
            }
        }
    }
    override fun observeChanges(): Flow<Unit> = merge(dao.observeCount().map { }, learning.observeState().map { })
    override fun isEnabled(): Boolean = settingsRepository.isSpamDetectionEnabled()
}
