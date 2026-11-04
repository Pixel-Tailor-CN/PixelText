package vip.mystery0.pixel.text.domain.spam.learning

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.resource.HubModelBundle
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.worker.PersonalLearningScheduler

class PersonalLearningCoordinator(
    private val repository: PersonalLearningRepository,
    private val settings: AppSettingsRepository,
    private val scheduler: PersonalLearningScheduler,
    private val engineFactory: () -> SpamModelEngine,
    private val candidateEngineFactory: (HubModelBundle) -> SpamModelEngine,
    private val onChanged: suspend () -> Unit = {},
) {
    private val trainingGate = Mutex()
    private val mutationGate = Mutex()
    val state = repository.observeState().stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, LearningState()
    )

    // 补排样本事务完成后、WorkManager 入队前进程终止的窗口。
    init {
        if (settings.settings.value.personalLearningEnabled) scheduler.recover()
    }

    fun requestLearning() {
        if (settings.settings.value.personalLearningEnabled) scheduler.schedule()
    }

    suspend fun setEnabled(enabled: Boolean) = mutationGate.withLock {
        repository.withDecision {
            if (settings.settings.value.personalLearningEnabled != enabled) {
                // 开关变化也推进代次，取消之前已开始的候选提交资格。
                repository.invalidatePersonalParameters()
                settings.setPersonalLearningEnabled(enabled)
            }
        }
        if (enabled) requestLearning() else scheduler.cancel()
        onChanged()
    }

    suspend fun record(key: LearningMessageKey, text: String, label: LearningLabel): Long = mutationGate.withLock {
        check(settings.settings.value.personalLearningEnabled) { "personal learning disabled" }
        val revision = repository.record(key, text, label)
        repository.updateStatus(revision, LearningStatus.QUEUED)
        requestLearning()
        onChanged()
        revision
    }

    suspend fun deleteSample(id: String) = mutationGate.withLock {
        repository.deleteSample(id)
        requestLearning()
        onChanged()
    }

    suspend fun clear() = mutationGate.withLock {
        repository.clear()
        scheduler.cancel()
        onChanged()
    }

    suspend fun invalidatePersonalParameters(revision: Long) = mutationGate.withLock {
        repository.withDecision {
            if (repository.stateRevision() == revision) repository.invalidatePersonalParameters()
        }
        requestLearning()
        onChanged()
    }

    suspend fun runLearning(): Boolean = trainingGate.withLock {
        withContext(Dispatchers.Default) {
            if (!settings.settings.value.personalLearningEnabled) return@withContext true
            val snapshot = repository.snapshot()
            if (snapshot.samples.isEmpty()) {
                repository.updateStatus(snapshot.revision, LearningStatus.IDLE)
                return@withContext true
            }
            repository.updateStatus(snapshot.revision, LearningStatus.RUNNING)
            try {
                engineFactory().use { engine ->
                    if (repository.currentParameters(engine.identity)?.generation == snapshot.revision) {
                        repository.updateStatus(snapshot.revision, LearningStatus.APPLIED)
                        return@withContext true
                    }
                    val parameters = train(engine, snapshot)
                    val applied = repository.withDecision {
                        currentCoroutineContext().ensureActive()
                        if (!settings.settings.value.personalLearningEnabled || repository.stateRevision() != snapshot.revision) {
                            false
                        } else {
                            // 基础组合更换后，旧解释器得到的候选不可激活。
                            engineFactory().use { current ->
                                current.identity == engine.identity && repository.commitParameters(snapshot.revision, parameters)
                            }
                        }
                    }
                    if (applied) onChanged()
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                repository.updateStatus(snapshot.revision, LearningStatus.FAILED, "本地学习失败，可重试")
                false
            }
        }
    }

    /** 候选组合先完成学习，再在公共决策锁内切换；失败保留旧组合。 */
    suspend fun activateBundle(candidate: HubModelBundle, commit: suspend () -> Unit): Boolean = trainingGate.withLock {
        withContext(Dispatchers.Default) {
            val snapshot = repository.snapshot()
            val enabled = settings.settings.value.personalLearningEnabled
            try {
                candidateEngineFactory(candidate).use { engine ->
                    val parameters = if (enabled && snapshot.samples.isNotEmpty()) train(engine, snapshot) else null
                    val applied = repository.withDecision {
                        currentCoroutineContext().ensureActive()
                        if (repository.stateRevision() != snapshot.revision || settings.settings.value.personalLearningEnabled != enabled) {
                            false
                        } else {
                            // 参数按模型身份隔离；切换中断时旧组合仍能恢复自己的参数。
                            if (parameters != null && !repository.commitParameters(snapshot.revision, parameters)) return@withDecision false
                            commit()
                            if (parameters == null) repository.invalidatePersonalParameters()
                            true
                        }
                    }
                    if (applied) onChanged()
                    applied
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun train(engine: SpamModelEngine, snapshot: LearningSnapshot): PersonalParameters {
        var payload = engine.reset()
        require(engine.epochs in 1..100 && payload.size == engine.parameterCount)
        val random = java.util.Random(42L)
        repeat(engine.epochs) {
            // 同一快照以固定种子逐轮洗牌，重建结果可复现。
            val ordered = snapshot.samples.toMutableList()
            java.util.Collections.shuffle(ordered, random)
            for (sample in ordered) {
                currentCoroutineContext().ensureActive()
                check(settings.settings.value.personalLearningEnabled) { "personal learning disabled" }
                check(repository.stateRevision() == snapshot.revision) { "stale learning snapshot" }
                payload = engine.train(sample.text, if (sample.label == LearningLabel.SPAM) 1f else 0f, payload)
                require(payload.size == engine.parameterCount && payload.all { it.isFinite() })
            }
        }
        return PersonalParameters(engine.identity, snapshot.revision, payload)
    }
}
