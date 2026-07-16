package vip.mystery0.pixel.text.worker

import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import java.util.concurrent.TimeUnit

class VerificationCodeCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val settingsRepository: AppSettingsRepository by inject()
    private val verificationCodeRepository: VerificationCodeRepository by inject()
    private val messageRepository: MessageRepository by inject()

    override suspend fun doWork(): Result {
        if (!settingsRepository.isVerificationCodeAutoDeleteEnabled()) {
            Log.i(TAG, "verification cleanup skipped reason=disabled")
            return Result.success()
        }
        if (Telephony.Sms.getDefaultSmsPackage(applicationContext) != applicationContext.packageName) {
            Log.w(TAG, "verification cleanup unavailable reason=not_default_sms")
            return Result.retry()
        }

        val retentionDays = settingsRepository.getVerificationCodeRetentionDays().coerceIn(
            AppSettingsKeys.MIN_VERIFICATION_CODE_RETENTION_DAYS,
            AppSettingsKeys.MAX_VERIFICATION_CODE_RETENTION_DAYS,
        )
        val cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())

        return try {
            val expiredIds = verificationCodeRepository.getExpiredMessageIds(cutoffTimestamp)
            var deletedCount = 0
            expiredIds.chunked(DELETE_BATCH_SIZE).forEach { batch ->
                deletedCount += messageRepository.deleteMessages(batch.toSet())
            }
            Log.i(
                TAG,
                "verification cleanup completed candidates=${expiredIds.size} deleted=$deletedCount retention_days=$retentionDays",
            )
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(
                TAG,
                "verification cleanup failed attempt=$runAttemptCount error=${error::class.java.simpleName}",
                error,
            )
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "VerificationCleanup"
        const val DELETE_BATCH_SIZE = 500
    }
}
