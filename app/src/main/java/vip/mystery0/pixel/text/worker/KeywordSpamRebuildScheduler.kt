package vip.mystery0.pixel.text.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class KeywordSpamRebuildScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun schedule() {
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<KeywordSpamRebuildWorker>().build(),
        )
    }

    fun observeState(): Flow<WorkInfo.State?> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
            .map { workInfos -> workInfos.lastOrNull()?.state }

    companion object {
        const val UNIQUE_WORK_NAME = "keyword_spam_rebuild"
    }
}
