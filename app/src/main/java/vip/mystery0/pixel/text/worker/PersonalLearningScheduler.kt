package vip.mystery0.pixel.text.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class PersonalLearningScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    fun recover() = enqueue(ExistingWorkPolicy.KEEP)
    fun schedule() = enqueue(ExistingWorkPolicy.REPLACE)
    private fun enqueue(policy: ExistingWorkPolicy) {
        // 仅持久化任务身份，正文只从私有学习库读取。
        workManager.enqueueUniqueWork(NAME, policy,
            OneTimeWorkRequestBuilder<PersonalLearningWorker>().build())
    }
    fun cancel() { workManager.cancelUniqueWork(NAME) }
    companion object { private const val NAME = "personal_learning" }
}
