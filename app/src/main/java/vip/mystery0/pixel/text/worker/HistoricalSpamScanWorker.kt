package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.notification.SpamScanNotificationHelper
import java.util.UUID

class HistoricalSpamScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        private const val TAG = "HistoricalSpamScanWorker"
        private const val BATCH_SIZE = 200
        private const val SPAM_THRESHOLD = 0.7f
        private const val NOTIFICATION_UPDATE_INTERVAL = 5

        const val UNIQUE_WORK_NAME = "historical_spam_scan"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_SPAM_COUNT = "spam_count"

        fun schedule(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<HistoricalSpamScanWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }
    }

    private val telephonyDataSource: TelephonyDataSource by inject()
    private val spamRepository: SpamRepository by inject()
    private val spamClassifierFactory: SpamClassifierFactory by inject()

    override suspend fun doWork(): Result {
        if (!spamRepository.isEnabled()) return Result.success()

        return try {
            val allMessages = telephonyDataSource.getSmsMessagesForSpamScan()
            val identifiedIds =
                spamRepository.getIdentifiedMessageIds(allMessages.map { it.messageId })
            val pendingMessages = allMessages.filter { it.messageId !in identifiedIds }

            var processed = 0
            var spamCount = 0
            publishProgress(processed, pendingMessages.size, spamCount, forceNotification = true)

            if (pendingMessages.isNotEmpty()) {
                spamClassifierFactory.create().use { classifier ->
                    pendingMessages.chunked(BATCH_SIZE).forEach { batch ->
                        batch.forEach { message ->
                            val score = classifier.classify(message.content)
                            if (score >= 0f) {
                                spamRepository.save(message.messageId, message.threadId, score)
                                if (score >= SPAM_THRESHOLD) spamCount++
                            }
                            processed++
                            publishProgress(processed, pendingMessages.size, spamCount)
                        }
                    }
                }
            }

            publishProgress(processed, pendingMessages.size, spamCount, forceNotification = true)
            SpamScanNotificationHelper.showCompleted(applicationContext, processed, spamCount)
            Result.success(
                workDataOf(
                    KEY_TOTAL to pendingMessages.size,
                    KEY_PROCESSED to processed,
                    KEY_SPAM_COUNT to spamCount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "historical spam scan failed", e)
            SpamScanNotificationHelper.showFailed(applicationContext)
            Result.failure()
        }
    }

    private suspend fun publishProgress(
        processed: Int,
        total: Int,
        spamCount: Int,
        forceNotification: Boolean = false
    ) {
        setProgress(
            workDataOf(
                KEY_TOTAL to total,
                KEY_PROCESSED to processed,
                KEY_SPAM_COUNT to spamCount
            )
        )

        if (forceNotification ||
            processed == total ||
            processed % NOTIFICATION_UPDATE_INTERVAL == 0
        ) {
            SpamScanNotificationHelper.showProgress(applicationContext, processed, total)
        }
    }
}
