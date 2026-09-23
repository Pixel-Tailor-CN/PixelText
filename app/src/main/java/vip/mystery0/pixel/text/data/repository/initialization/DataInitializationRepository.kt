package vip.mystery0.pixel.text.data.repository.initialization

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.toEntity
import vip.mystery0.pixel.text.domain.model.CURRENT_DATA_VERSION
import vip.mystery0.pixel.text.domain.model.DataInitializationState
import vip.mystery0.pixel.text.domain.model.InitializationPhase
import vip.mystery0.pixel.text.domain.model.InitializationStatus

/** 状态读取没有调度、Provider 访问或解析副作用。 */
class DataInitializationRepository(private val database: MessageMirrorDatabase) {
    private val dao get() = database.initializationDao()

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
