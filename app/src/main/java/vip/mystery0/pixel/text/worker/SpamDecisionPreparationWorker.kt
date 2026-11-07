package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.repository.MirrorSpamScanner
import vip.mystery0.pixel.text.domain.spam.SpamRepository

/** 旧版标记迁移和中断恢复属于独立维护任务，识别任务只等待完成标记。 */
class SpamDecisionPreparationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    private val scanner: MirrorSpamScanner by inject()
    private val repository: SpamRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            if (!scanner.isReady()) {
                repository.ensureReady()
                if (!scanner.isReady()) return Result.retry()
                if (scanner.isEnabled()) HistoricalSpamScanWorker.schedule(applicationContext, silent = true)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("SpamDecisionPreparation", "spam decision preparation failed", error)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("spam_decision_preparation", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SpamDecisionPreparationWorker>().build())
        }
    }
}
