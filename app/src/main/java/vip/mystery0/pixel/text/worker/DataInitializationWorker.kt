package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.repository.initialization.DataInitializationCoordinator
import vip.mystery0.pixel.text.data.repository.initialization.DataInitializationRepository
import vip.mystery0.pixel.text.data.repository.mms.MmsTextIndexer
import vip.mystery0.pixel.text.data.source.requireSmsAccess
import vip.mystery0.pixel.text.domain.model.InitializationStatus
import vip.mystery0.pixel.text.domain.model.InitializationStepResult

class DataInitializationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters), KoinComponent {
    private val repository: DataInitializationRepository by inject()
    private val coordinator: DataInitializationCoordinator by inject()
    private val indexer: MmsTextIndexer by inject()
    private val scheduler: DataInitializationScheduler by inject()
    private val database: vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase by inject()

    override suspend fun doWork(): Result {
        val initial = repository.read()
        if (initial.isCurrent || initial.isNewerVersion) return Result.success()
        return try {
            applicationContext.requireSmsAccess()
            when (val result = coordinator.runSlice()) {
                InitializationStepResult.Complete -> {
                    indexer.start()
                    getKoin().get<MessageMirrorScheduler>()
                        .schedule(reason = "initialization_complete")
                    Result.success()
                }

                InitializationStepResult.More -> {
                    val latest = repository.read()
                    val yielded = listOf("SMS", "MMS").any {
                        database.syncDao().state(it)?.error == "budget_yield"
                    }
                    val advanced = latest.nextPhase != initial.nextPhase ||
                        latest.afterLocalId != initial.afterLocalId
                    if (yielded || advanced) {
                        scheduler.enqueueContinuation()
                        Log.i("DataInitialization", "initialization continuation phase=${latest.nextPhase} checkpoint=${latest.afterLocalId}")
                        Result.success()
                    } else Result.retry()
                }
                is InitializationStepResult.Blocked -> {
                    failure(result.category, InitializationStatus.FAILED)
                    Result.failure()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            failure("permission_required", InitializationStatus.WAITING_PERMISSION)
            Result.failure()
        } catch (error: Exception) {
            failure(error.javaClass.simpleName, InitializationStatus.FAILED)
            Result.retry()
        } finally {
            repository.pauseProgress()
        }
    }

    private suspend fun failure(category: String, status: InitializationStatus) {
        val state = repository.read()
        if (!state.isCurrent && !state.isNewerVersion) {
            repository.save(state.epoch, state.copy(status = status, errorCategory = category))
        }
        Log.w(
            "DataInitialization",
            "initialization incomplete category=$category attempt=$runAttemptCount"
        )
    }
}
