package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DataInitializationDao {
    @Query("SELECT * FROM data_initialization WHERE id=1")
    abstract suspend fun read(): DataInitializationEntity?

    @Query("SELECT * FROM data_initialization WHERE id=1")
    abstract fun observe(): Flow<DataInitializationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(state: DataInitializationEntity)

    /** 检查和写入处于同一事务，旧任务不能覆盖输入变更后的状态。 */
    @Transaction
    open suspend fun save(expectedEpoch: Long, state: DataInitializationEntity): Boolean {
        val current = read() ?: return false
        if (current.epoch != expectedEpoch || current.targetVersion != state.targetVersion) return false
        if (state.epoch != expectedEpoch || state.completedVersion < current.completedVersion) return false
        if (state.completedVersion != current.completedVersion &&
            (current.nextPhase != "VERIFICATION" || state.nextPhase != "COMPLETE" ||
                    state.status != "COMPLETE" || state.completedVersion != state.targetVersion)
        ) return false
        put(state)
        return true
    }
}
