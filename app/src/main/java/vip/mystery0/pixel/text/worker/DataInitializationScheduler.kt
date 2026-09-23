package vip.mystery0.pixel.text.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.repository.initialization.DataInitializationRepository
import vip.mystery0.pixel.text.domain.model.InitializationStatus

class DataInitializationScheduler(
    private val context: Context,
    private val repository: DataInitializationRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun checkOnLaunch() = schedule(false)
    fun retry() = schedule(true)

    private fun schedule(retry: Boolean) {
        scope.launch {
            try {
                mutex.withLock {
                    val state = repository.read()
                    if (state.isCurrent || state.isNewerVersion) return@withLock
                    val pending = repository.prepare()
                    if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                        repository.save(
                            pending.epoch,
                            pending.copy(status = InitializationStatus.WAITING_PERMISSION)
                        )
                        return@withLock
                    }
                    val work = WorkManager.getInstance(context)
                    val running = if (retry) work.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
                        .any { it.state == androidx.work.WorkInfo.State.RUNNING } else false
                    work.enqueueUniqueWork(
                        UNIQUE_WORK_NAME,
                        if (retry && !running) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<DataInitializationWorker>().build(),
                    ).result.get()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(
                    "DataInitialization",
                    "initialization scheduling failed category=${error.javaClass.simpleName}"
                )
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "data-initialization"
    }
}
