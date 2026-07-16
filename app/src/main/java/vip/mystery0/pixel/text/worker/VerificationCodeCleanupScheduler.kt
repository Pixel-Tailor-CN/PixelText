package vip.mystery0.pixel.text.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import java.util.concurrent.TimeUnit

class VerificationCodeCleanupScheduler(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun sync() {
        if (!settingsRepository.isVerificationCodeAutoDeleteEnabled()) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<VerificationCodeCleanupWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).setInitialDelay(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS).build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "verification_code_auto_cleanup"
        const val REPEAT_INTERVAL_HOURS = 5L
    }
}
