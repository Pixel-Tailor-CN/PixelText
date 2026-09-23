package vip.mystery0.pixel.text.data.repository.initialization

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.backup.RestoreSafetyCoordinator
import java.io.IOException

/** 锁顺序：输入保护 → 镜像锁/索引内部锁；不从镜像回调反向申请本锁。 */
class DataInitializationGuard(
    private val repository: DataInitializationRepository,
    private val safety: RestoreSafetyCoordinator,
) {
    private val mutex = Mutex()

    suspend fun <T> withStableInputs(block: suspend () -> T): T = mutex.withLock {
        if (safety.active) throw IOException("restore_in_progress")
        block()
    }

    suspend fun <T> withInputMutation(block: suspend () -> T): T = mutex.withLock {
        repository.invalidatePending()
        block()
    }
}
