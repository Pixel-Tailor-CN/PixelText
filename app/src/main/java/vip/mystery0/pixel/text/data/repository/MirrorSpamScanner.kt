package vip.mystery0.pixel.text.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.BlockedKeywordEntity
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamScanRow
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamUpdate
import vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.spam.learning.ModelContract
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.MessageSpamSource
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistMatcher
import vip.mystery0.pixel.text.domain.spam.SpamClassifier
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.SpamPrediction
import vip.mystery0.pixel.text.domain.spam.SpamPredictionSession
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType
import vip.mystery0.pixel.text.domain.spam.learning.PersonalLearningRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class StaleSpamScanSessionException : Exception("spam scan model changed during session creation")

class MirrorSpamScanSession internal constructor(
    internal val classifiers: List<SpamClassifier>,
    internal val prediction: SpamPredictionSession?,
) : AutoCloseable {
    override fun close() {
        var failure: Throwable? = null
        classifiers.asReversed().forEach { classifier ->
            try {
                classifier.close()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

data class MirrorSpamScanBatch(
    val afterLocalId: Long,
    val processed: Int,
    val spamCount: Int,
    val complete: Boolean,
    val stale: Boolean = false,
    val paused: Boolean = false,
)

data class MirrorSpamScanStats(val total: Int, val pending: Int)

/** 历史和初始化识别共用镜像批处理；此链路不访问 Provider，也不修复镜像。 */
class MirrorSpamScanner(
    private val database: MessageMirrorDatabase,
    private val settings: AppSettingsRepository,
    private val learning: PersonalLearningRepository,
    private val resources: HubResourceStore,
    private val classifiers: SpamClassifierFactory,
) {
    private val dao = database.spamDao()
    fun isEnabled(): Boolean = settings.isSpamDetectionEnabled()
    suspend fun isReady(): Boolean = database.syncDao().spamScanReady()

    /** 确认前仍检查会话，不能由旧模型或未恢复的人工投影完成新一轮扫描。 */
    suspend fun complete(session: MirrorSpamScanSession, contentGeneration: Long?): Boolean = withContext(Dispatchers.IO) {
        learning.withDecision {
            if (!isReady() || !isCurrent(session.prediction)) return@withDecision false
            contentGeneration?.let { database.mirrorDao().completeSpamContentChange(it) }
            true
        }
    }

    suspend fun statistics(): MirrorSpamScanStats = withContext(Dispatchers.IO) {
        learning.withDecision {
            val modelEnabled = isEnabled()
            val identity = if (modelEnabled) ModelContract.parse(resources.currentModelBundle().contractFile.readText()).identity else null
            val revision = if (modelEnabled) learning.stateRevision() else null
            val generation = if (identity != null && settings.settings.value.personalLearningEnabled)
                learning.currentParameters(identity)?.generation else null
            database.withTransaction {
                val upper = database.mirrorDao().maximumLocalId() ?: 0L
                MirrorSpamScanStats(dao.scanTotal(upper), dao.scanCount(upper, modelEnabled, identity, revision, generation))
            }
        }
    }

    suspend fun openSession(parallelism: Int = 1): MirrorSpamScanSession {
        require(parallelism in 1..2)
        val instances = mutableListOf<SpamClassifier>()
        return try {
            withContext(Dispatchers.Default) {
                if (!isEnabled()) return@withContext MirrorSpamScanSession(emptyList(), null)
                val first = classifiers.create().also(instances::add)
                val prediction = first.openSession()
                repeat(minOf(parallelism, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)) - 1) {
                    currentCoroutineContext().ensureActive()
                    val next = classifiers.create().also(instances::add)
                    if (next.modelIdentity != prediction.modelIdentity) throw StaleSpamScanSessionException()
                }
                MirrorSpamScanSession(instances.toList(), prediction)
            }
        } catch (error: Throwable) {
            // 包括切回调用协程时的取消，不能遗失已经创建的模型实例。
            try {
                MirrorSpamScanSession(instances.toList(), null).close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    suspend fun count(session: MirrorSpamScanSession, upperLocalId: Long): Int = withContext(Dispatchers.IO) {
        val prediction = session.prediction
        dao.scanCount(upperLocalId, prediction != null, prediction?.modelIdentity,
            prediction?.revision, prediction?.parameters?.generation)
    }

    suspend fun scanBatch(
        session: MirrorSpamScanSession,
        afterLocalId: Long,
        upperLocalId: Long,
        limit: Int = 200,
        onProcessed: (Int) -> Unit = {},
    ): MirrorSpamScanBatch = withContext(Dispatchers.Default) {
        require(limit in 1..400)
        val startedAt = SystemClock.elapsedRealtime()
        val predictionSession = session.prediction
        val (rows, rules) = withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.scanBatch(afterLocalId, upperLocalId, limit, predictionSession != null,
                    predictionSession?.modelIdentity, predictionSession?.revision,
                    predictionSession?.parameters?.generation) to readRules()
            }
        }
        if (rows.isEmpty()) {
            val valid = withContext(Dispatchers.IO) { learning.withDecision { isReady() && isCurrent(predictionSession) } }
            return@withContext MirrorSpamScanBatch(afterLocalId, 0, 0, complete = valid, stale = !valid)
        }
        val readAt = SystemClock.elapsedRealtime()
        val predictions = mutableMapOf<Long, SpamPrediction>()
        // 规则匹配在分发前完成，推理协程各自独占解释器，只共享不可变参数快照。
        val needsPrediction = rows.map { predictionSession != null && it.body.orEmpty().isNotBlank() && !rules.allows(it) }
        val nextIndex = AtomicInteger()
        val paused = AtomicBoolean()
        val outcomes = Channel<Pair<Long, SpamPrediction?>>(rows.size)
        try {
            coroutineScope {
                val lanes: List<SpamClassifier?> = if (predictionSession == null) listOf(null) else session.classifiers
                val jobs = lanes.map { classifier ->
                    async {
                        while (!paused.get()) {
                            currentCoroutineContext().ensureActive()
                            if (predictionSession != null && !isEnabled()) {
                                paused.set(true)
                                break
                            }
                            val index = nextIndex.getAndIncrement()
                            if (index >= rows.size) break
                            val row = rows[index]
                            val prediction = if (needsPrediction[index]) requireNotNull(classifier)
                                .predict(row.body.orEmpty(), requireNotNull(predictionSession)) else null
                            outcomes.send(row.state.localId to prediction)
                        }
                    }
                }
                launch {
                    jobs.awaitAll()
                    outcomes.close()
                }
                var processed = 0
                for ((localId, prediction) in outcomes) {
                    if (prediction != null) predictions[localId] = prediction
                    // 单个汇总协程保证计数递增，回调仍只更新内存，不等待进度 I/O。
                    onProcessed(++processed)
                }
            }
        } finally {
            outcomes.cancel()
        }
        if (paused.get()) {
            return@withContext MirrorSpamScanBatch(afterLocalId, 0, 0, complete = false, paused = true)
        }
        val inferredAt = SystemClock.elapsedRealtime()
        val result = withContext(Dispatchers.IO) {
            learning.withDecision {
                if (predictionSession != null && !isEnabled()) {
                    return@withDecision MirrorSpamScanBatch(afterLocalId, 0, 0, complete = false, paused = true)
                }
                if (!isReady() || !isCurrent(predictionSession)) {
                    return@withDecision MirrorSpamScanBatch(afterLocalId, 0, 0, complete = false, stale = true)
                }
                MirrorSynchronizationLock.mutex.withLock {
                    database.withTransaction { saveBatch(rows, predictions, predictionSession, rows.size < limit) }
                }
            }
        }
        Log.i("MirrorSpamScanner", "spam scan batch count=${rows.size} parallelism=${session.classifiers.size} read_ms=${readAt - startedAt} " +
            "infer_ms=${inferredAt - readAt} save_ms=${SystemClock.elapsedRealtime() - inferredAt} stale=${result.stale}")
        result
    }

    private suspend fun isCurrent(session: SpamPredictionSession?): Boolean {
        if (session == null) return !isEnabled()
        if (settings.settings.value.personalLearningEnabled != session.personalEnabled ||
            learning.stateRevision() != session.revision) return false
        val identity = ModelContract.parse(resources.currentModelBundle().contractFile.readText()).identity
        if (identity != session.modelIdentity) return false
        val generation = if (session.personalEnabled) learning.currentParameters(identity)?.generation else null
        return generation == session.parameters?.generation
    }

    /** 调用方已持有学习决策锁、镜像锁及主库事务，按当前人工标记和规则合并。 */
    private suspend fun saveBatch(
        scanned: List<MirrorSpamScanRow>,
        predictions: Map<Long, SpamPrediction>,
        session: SpamPredictionSession?,
        complete: Boolean,
    ): MirrorSpamScanBatch {
        val current = dao.scanRows(scanned.map { it.state.localId }).associateBy { it.state.localId }
        val rules = readRules()
        val updates = mutableListOf<MirrorSpamUpdate>()
        var stale = false
        var spamCount = 0
        for ((originalState) in scanned) {
            currentCoroutineContext().ensureActive()
            val row = current[originalState.localId] ?: continue
            if (row.state.messageRevision != originalState.messageRevision || row.state.messageId != originalState.messageId) {
                stale = true
                continue
            }
            val allowed = rules.allows(row)
            val prediction = predictions[row.state.localId]
            if (!allowed && session != null && row.body.orEmpty().isNotBlank() && prediction == null) {
                // 推理前命中的白名单已被删除，不能用缺失的分数确认本条完成。
                stale = true
                continue
            }
            var next = row.state.copy(
                spamAllowedSource = row.state.spamAllowedSource ?: if (allowed) MessageSpamSource.WHITELIST else null,
                spamKeywordId = if (allowed) null else rules.keyword(row.body.orEmpty()),
            )
            if (prediction != null) {
                // 个人参数恢复会推进代次，旧批次不得混入新代次的结果。
                if (session == null || prediction.modelIdentity != session.modelIdentity || prediction.revision != session.revision ||
                    prediction.personalGeneration != session.parameters?.generation) {
                    stale = true
                    continue
                }
                next = next.copy(spamScore = prediction.score, spamModelIdentity = prediction.modelIdentity,
                    spamModelRevision = prediction.revision, spamModelGeneration = prediction.personalGeneration,
                    spamModelManual = false)
            }
            next = next.resolved()
            if (next.isSpam == true) spamCount++
            if (next != row.state) updates += next.update()
        }
        if (updates.isNotEmpty()) dao.updateScanStates(updates)
        return MirrorSpamScanBatch(scanned.last().state.localId, scanned.size, spamCount, complete, stale)
    }

    private suspend fun readRules(): Rules = Rules(database.blockedKeywordDao().getAll(),
        database.senderWhitelistDao().getRules().mapNotNull { rule ->
            runCatching { SenderWhitelistMatcher(WhitelistRuleType.valueOf(rule.type), rule.value) }.getOrNull()
        })

    private class Rules(val keywords: List<BlockedKeywordEntity>, val whitelist: List<SenderWhitelistMatcher>) {
        fun allows(row: MirrorSpamScanRow): Boolean = row.state.spamAllowedSource != null ||
            whitelist.any { it.matches(row.address.orEmpty()) }
        fun keyword(body: String): Long? {
            if (keywords.isEmpty() || body.isEmpty()) return null
            val normalized = body.lowercase(Locale.ROOT)
            return keywords.firstOrNull { it.normalizedKeyword in normalized }?.id
        }
    }
}
