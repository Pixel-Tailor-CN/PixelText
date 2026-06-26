package vip.mystery0.pixel.text.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.notification.ResourceUpdateNotificationHelper
import java.util.concurrent.TimeUnit

class ResourceUpdateScheduler(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun syncAfterSettingsChange() {
        if (!settingsRepository.isResourceAutoCheckEnabled()) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK_NAME)
            ResourceUpdateNotificationHelper.cancel(appContext)
            return
        }

        workManager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK_NAME)
        enqueuePeriodicWork()
    }

    fun syncOnAppStart() {
        if (!settingsRepository.isResourceAutoCheckEnabled()) {
            syncAfterSettingsChange()
            return
        }

        enqueuePeriodicWork()
        val intervalMillis = TimeUnit.HOURS.toMillis(
            settingsRepository.getResourceAutoCheckIntervalHours().coerceAtLeast(1L)
        )
        val lastCheckedAt = settingsRepository.getResourceAutoCheckLastCheckedAt()
        val now = System.currentTimeMillis()
        if (lastCheckedAt <= 0L || now - lastCheckedAt >= intervalMillis) {
            enqueueImmediateCheck()
        }
    }

    fun enqueueImmediateCheck() {
        val request = OneTimeWorkRequestBuilder<ResourceUpdateCheckWorker>()
            .setConstraints(networkConstraints())
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun enqueuePeriodicWork() {
        val intervalHours = settingsRepository.getResourceAutoCheckIntervalHours()
            .coerceAtLeast(1L)
        val request = PeriodicWorkRequestBuilder<ResourceUpdateCheckWorker>(
            intervalHours,
            TimeUnit.HOURS
        )
            .setInitialDelay(intervalHours, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun networkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    private companion object {
        private const val UNIQUE_PERIODIC_WORK_NAME = "resource_update_auto_check"
        private const val UNIQUE_IMMEDIATE_WORK_NAME = "resource_update_immediate_check"
    }
}
