package vip.mystery0.pixel.text.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.repository.initialization.DataInitializationCoordinator
import vip.mystery0.pixel.text.data.repository.initialization.DataInitializationRepository
import vip.mystery0.pixel.text.data.repository.mms.MmsTextIndexer
import vip.mystery0.pixel.text.domain.model.InitializationStatus
import vip.mystery0.pixel.text.domain.model.InitializationStepResult

class DataInitializationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters), KoinComponent {
    private val repository: DataInitializationRepository by inject()
    private val coordinator: DataInitializationCoordinator by inject()
    private val indexer: MmsTextIndexer by inject()

    override suspend fun doWork(): Result {
        val initial = repository.read()
        if (initial.isCurrent || initial.isNewerVersion) return Result.success()
        return try {
            if (applicationContext.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("sms_permission_required")
            }
            when (val result = coordinator.runSlice()) {
                InitializationStepResult.Complete -> {
                    indexer.start()
                    getKoin().get<MessageMirrorScheduler>()
                        .schedule(reason = "initialization_complete")
                    Result.success()
                }

                InitializationStepResult.More -> Result.retry()
                is InitializationStepResult.Blocked -> {
                    failure(result.category, InitializationStatus.FAILED)
                    Result.failure()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            failure("permission_required", InitializationStatus.WAITING_PERMISSION)
            Result.failure()
        } catch (error: Exception) {
            failure(error.javaClass.simpleName, InitializationStatus.FAILED)
            Result.retry()
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
