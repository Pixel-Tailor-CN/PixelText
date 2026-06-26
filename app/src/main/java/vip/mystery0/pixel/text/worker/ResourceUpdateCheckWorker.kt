package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.repository.HubResourceRepository
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateAvailability
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.notification.ResourceUpdateNotificationHelper

class ResourceUpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val settingsRepository: AppSettingsRepository by inject()
    private val hubResourceRepository: HubResourceRepository by inject()

    override suspend fun doWork(): Result {
        if (!settingsRepository.isResourceAutoCheckEnabled()) {
            ResourceUpdateNotificationHelper.cancel(applicationContext)
            return Result.success()
        }

        return try {
            when (val result = hubResourceRepository.checkResourceUpdateAvailability()) {
                is ResourceUpdateAvailability.Available -> {
                    settingsRepository.setResourceAutoCheckLastCheckedAt(System.currentTimeMillis())
                    ResourceUpdateNotificationHelper.showUpdateAvailable(
                        applicationContext,
                        result.detail
                    )
                }

                is ResourceUpdateAvailability.NoUpdate -> {
                    settingsRepository.setResourceAutoCheckLastCheckedAt(System.currentTimeMillis())
                    ResourceUpdateNotificationHelper.cancel(applicationContext)
                }
            }
            Result.success()
        } catch (error: Exception) {
            Log.e(
                TAG,
                "resource update check failed error=${error::class.java.simpleName}",
                error
            )
            Result.retry()
        }
    }

    private companion object {
        private const val TAG = "ResourceUpdateCheckWorker"
    }
}
