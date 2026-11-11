package vip.mystery0.pixel.text.data.repository.mirror

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes provider restoration against ordinary mirror work.
 *
 * Provider writes performed by a restore emit one callback per message. Keeping the
 * gate for the whole restore lets the restore finish first; the caller then requests
 * one authoritative reconciliation instead of starting a scan for every callback.
 */
class RestoreSyncCoordinator {
    private val gate = Mutex()

    @Volatile
    private var restorePaused = false

    fun isRestorePaused(): Boolean = restorePaused

    suspend fun acquireRestore() {
        gate.lock()
        restorePaused = true
    }

    fun releaseRestore() {
        check(restorePaused) { "restore synchronization is not paused" }
        restorePaused = false
        gate.unlock()
    }

    suspend fun <T> runIfAllowed(block: suspend () -> T): T? = gate.withLock {
        if (restorePaused) null else block()
    }
}
