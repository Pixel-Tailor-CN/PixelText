package vip.mystery0.pixel.text.data.repository.initialization

import kotlin.time.Duration.Companion.seconds
import androidx.room.withTransaction
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.toEntity
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.CURRENT_DATA_VERSION
import vip.mystery0.pixel.text.domain.model.DataInitializationProgress
import vip.mystery0.pixel.text.domain.model.DataInitializationState
import vip.mystery0.pixel.text.domain.model.InitializationPhase
import vip.mystery0.pixel.text.domain.model.InitializationStatus

/** 状态读取没有调度、Provider 访问或解析副作用。 */
class DataInitializationRepository(
    private val database: MessageMirrorDatabase,
    private val source: TelephonyDataSource,
) {
    private val dao get() = database.initializationDao()
    private data class Timing(
        val epoch: Long,
        val phase: InitializationPhase,
        val startedAt: Long,
        val baseline: Long,
        val elapsedBefore: Long = 0,
        val waiting: Boolean = false,
        val completed: Long? = null,
        val total: Long? = null,
    )
    private val timing = MutableStateFlow<Timing?>(null)

    /** 使用真实记录数而非不连续的 ID 比值；计数只随检查点变化查询。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeProgress(): Flow<DataInitializationProgress?> {
        val counts = observe().mapLatest { state -> state to phaseCounts(state) }
        val ticks = observe().map { !it.isCurrent && !it.isNewerVersion }
            .distinctUntilChanged().flatMapLatest { pending ->
                if (!pending) flowOf(SystemClock.elapsedRealtime()) else flow {
                    while (true) {
                        emit(SystemClock.elapsedRealtime())
                        delay(1.seconds)
                    }
                }
            }
        return combine(counts, database.syncDao().observeStates(), timing, ticks) { snapshot, sources, sample, now ->
            val (state, counts) = snapshot
            if (state.isCurrent || state.isNewerVersion) return@combine null
            val active = sample?.takeIf { it.epoch == state.epoch && it.phase == state.nextPhase }
            val completed = when (state.nextPhase) {
                InitializationPhase.MIRROR -> if (state.mirrorRound == null) 0L else
                    sources.filter { it.collection == "SMS" || it.collection == "MMS" }.sumOf { it.processedCount }
                InitializationPhase.VERIFICATION -> active?.completed ?: 0L
                else -> counts.first
            }
            val total = when (state.nextPhase) {
                InitializationPhase.MIRROR, InitializationPhase.VERIFICATION -> active?.total?.takeIf { completed <= it }
                else -> counts.second
            }
            val waiting = active == null || active.waiting || state.status != InitializationStatus.RUNNING
            val sampleElapsed = if (active == null || active.waiting) 0L else (now - active.startedAt).coerceAtLeast(0)
            val elapsed = (active?.elapsedBefore ?: 0L) + sampleElapsed
            val processed = completed - (active?.baseline ?: completed)
            val remaining = if (!waiting && total != null && completed < total && processed >= 20 && sampleElapsed >= 1_000)
                (sampleElapsed.toDouble() / processed * (total - completed)).toLong() else null
            DataInitializationProgress(state.nextPhase, completed, total, elapsed, remaining, waiting)
        }
    }

    suspend fun beginPhase(state: DataInitializationState) {
        val previous = timing.value
        if (previous?.epoch == state.epoch && previous.phase == state.nextPhase && !previous.waiting) return
        val completed = if (state.nextPhase == InitializationPhase.MIRROR && state.mirrorRound != null)
            listOf("SMS", "MMS").sumOf { database.syncDao().state(it)?.processedCount ?: 0L }
        else phaseCounts(state).first
        val samePhase = previous?.epoch == state.epoch && previous.phase == state.nextPhase
        val total = if (state.nextPhase == InitializationPhase.MIRROR) {
            val sms = source.getSmsIndexCount()
            val mms = source.getMmsCount()
            if (sms != null && mms != null) sms + mms else null
        } else null
        timing.value = Timing(
            state.epoch, state.nextPhase, SystemClock.elapsedRealtime(), completed,
            elapsedBefore = if (samePhase) previous.elapsedBefore else 0,
            total = total,
        )
    }

    fun reportVerificationProgress(completed: Long, total: Long?) {
        val previous = timing.value ?: return
        if (previous.phase == InitializationPhase.VERIFICATION && !previous.waiting) {
            timing.value = previous.copy(completed = completed, total = total)
        }
    }

    /** 暂停时冻结计时和速度样本，重试等待不参与剩余用时估算。 */
    fun pauseProgress() {
        val previous = timing.value ?: return
        if (!previous.waiting) timing.value = previous.copy(
            waiting = true,
            elapsedBefore = previous.elapsedBefore + (SystemClock.elapsedRealtime() - previous.startedAt).coerceAtLeast(0),
            // 验证码索引以整代提交；中断会丢弃未激活代，不能声称保留了分页进度。
            completed = if (previous.phase == InitializationPhase.VERIFICATION) 0L else previous.completed,
            total = if (previous.phase == InitializationPhase.VERIFICATION) null else previous.total,
        )
    }

    private suspend fun phaseCounts(state: DataInitializationState): Pair<Long, Long?> {
        val transport = when (state.nextPhase) {
            InitializationPhase.MMS_TEXT -> "MMS"
            InitializationPhase.SPAM -> "SMS"
            else -> return 0L to null
        }
        val upper = state.upperLocalId ?: return 0L to null
        val mirror = database.mirrorDao()
        return mirror.initializationCount(transport, state.afterLocalId.coerceAtMost(upper)) to
            mirror.initializationCount(transport, upper)
    }

    fun observe(): Flow<DataInitializationState> = dao.observe().map {
        it?.toState() ?: DataInitializationState()
    }

    suspend fun read(): DataInitializationState = dao.read()?.toState() ?: DataInitializationState()

    suspend fun prepare(targetVersion: Int = CURRENT_DATA_VERSION): DataInitializationState =
        withContext(Dispatchers.IO) {
            require(targetVersion > 0)
            database.withTransaction {
                val previous = dao.read()?.toState()
                if (previous != null && (previous.completedVersion >= targetVersion || previous.targetVersion >= targetVersion)) {
                    return@withTransaction previous
                }
                DataInitializationState(
                    completedVersion = previous?.completedVersion ?: 0,
                    targetVersion = targetVersion,
                    epoch = (previous?.epoch ?: 0) + 1,
                    updatedAt = System.currentTimeMillis(),
                ).also { dao.put(it.toEntity()) }
            }
        }

    suspend fun save(expectedEpoch: Long, state: DataInitializationState): Boolean {
        require(state.completedVersion <= state.targetVersion)
        if (state.status == InitializationStatus.COMPLETE) {
            require(state.nextPhase == InitializationPhase.COMPLETE)
            require(state.completedVersion == state.targetVersion)
        }
        return dao.save(
            expectedEpoch,
            state.copy(updatedAt = System.currentTimeMillis()).toEntity()
        )
    }

    /** 输入替换之前调用；已完成同版本交由既有资源/恢复流程处理，不重开升级。 */
    suspend fun invalidatePending() = database.withTransaction {
        val previous = dao.read()?.toState() ?: return@withTransaction
        if (previous.completedVersion >= previous.targetVersion) return@withTransaction
        dao.put(
            DataInitializationState(
                completedVersion = previous.completedVersion,
                targetVersion = previous.targetVersion,
                epoch = previous.epoch + 1,
                updatedAt = System.currentTimeMillis(),
            ).toEntity()
        )
    }
}
