package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.repository.initialization.DataInitializationRepository
import vip.mystery0.pixel.text.data.source.SmsAccessState
import vip.mystery0.pixel.text.data.source.smsAccessState
import vip.mystery0.pixel.text.domain.model.InitializationStatus

class DataInitializationScheduler(
    private val context: Context,
    private val repository: DataInitializationRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun checkOnLaunch() = schedule(false)
    fun retry() = schedule(true)

    /** 正常预算让出接续当前唯一任务链，不使用失败重试的指数退避。 */
    suspend fun enqueueContinuation() {
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DataInitializationWorker>().build(),
            ).result.get()
        }
    }

    private fun schedule(retry: Boolean) {
        scope.launch {
            try {
                mutex.withLock {
                    val state = repository.read()
                    if (state.isCurrent || state.isNewerVersion) return@withLock
                    val pending = repository.prepare()
                    if (context.smsAccessState() != SmsAccessState.READY) {
                        repository.save(
                            pending.epoch,
                            pending.copy(status = InitializationStatus.WAITING_PERMISSION)
                        )
                        return@withLock
                    }
                    val work = WorkManager.getInstance(context)
                    val running = retry && work.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
                        .any { it.state == androidx.work.WorkInfo.State.RUNNING }
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
