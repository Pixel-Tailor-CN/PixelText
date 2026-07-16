package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository

class VerificationCodeIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val repository: VerificationCodeRepository by inject()

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: MODE_RECONCILE
        return try {
            when (mode) {
                MODE_REBUILD -> repository.rebuildAll()
                MODE_RECONCILE -> repository.reconcile()
                else -> {
                    Log.e(TAG, "verification index mode invalid mode=$mode")
                    return Result.failure()
                }
            }
            Log.i(TAG, "verification index completed mode=$mode")
            Result.success()
        } catch (error: Exception) {
            Log.e(
                TAG,
                "verification index failed mode=$mode attempt=$runAttemptCount error=${error::class.java.simpleName}",
                error,
            )
            Result.retry()
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_RECONCILE = "reconcile"
        const val MODE_REBUILD = "rebuild"
        private const val TAG = "VerificationCodeIndexWorker"
    }
}
