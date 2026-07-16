package vip.mystery0.pixel.text.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class VerificationCodeIndexScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun scheduleReconcile() {
        schedule(VerificationCodeIndexWorker.MODE_RECONCILE)
    }

    fun scheduleFullRebuild() {
        schedule(VerificationCodeIndexWorker.MODE_REBUILD)
    }

    fun observeIsRunning(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).map { workInfos ->
            workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }

    fun observeLatestState(): Flow<WorkInfo.State?> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).map { it.lastOrNull()?.state }

    private fun schedule(mode: String) {
        val request = OneTimeWorkRequestBuilder<VerificationCodeIndexWorker>()
            .setInputData(
                Data.Builder()
                    .putString(VerificationCodeIndexWorker.KEY_MODE, mode)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "verification_code_index"
    }
}
