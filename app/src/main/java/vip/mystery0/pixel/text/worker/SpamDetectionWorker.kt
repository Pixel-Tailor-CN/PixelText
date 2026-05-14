package vip.mystery0.pixel.text.worker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.domain.spam.SpamClassifier
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.notification.SmsNotificationHelper

class SpamDetectionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        private const val TAG = "SpamDetectionWorker"
        private const val MAX_CONCURRENT_CLASSIFICATIONS = 10
        private const val SPAM_THRESHOLD = 0.7f
        private val classificationSemaphore = Semaphore(MAX_CONCURRENT_CLASSIFICATIONS)

        const val ACTION_SPAM_DETECTED = "vip.mystery0.pixel.text.action.SPAM_DETECTED"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_THREAD_ID = "thread_id"
        const val KEY_SENDER = "sender"
        const val KEY_CONTENT = "content"
        const val KEY_SCORE = "score"

        fun schedule(
            context: Context,
            messageId: Long,
            threadId: Long,
            sender: String,
            content: String
        ) {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_THREAD_ID to threadId,
                KEY_SENDER to sender,
                KEY_CONTENT to content
            )
            WorkManager.getInstance(context)
                .enqueue(
                    OneTimeWorkRequestBuilder<SpamDetectionWorker>().setInputData(data).build()
                )
        }
    }

    private val spamRepository: SpamRepository by inject()
    private val spamClassifier: SpamClassifier by inject()

    override suspend fun doWork(): Result {
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        val threadId = inputData.getLong(KEY_THREAD_ID, -1L)
        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val content = inputData.getString(KEY_CONTENT) ?: return Result.failure()
        if (messageId < 0 || threadId < 0) return Result.failure()

        if (!spamRepository.isEnabled()) return Result.success()

        val score = try {
            classificationSemaphore.withPermit {
                spamClassifier.classify(content)
            }
        } finally {
            spamClassifier.close()
        }
        if (score >= 0f) {
            spamRepository.save(messageId, threadId, score)
            Log.d(TAG, "spam score message_id=$messageId score=$score")
            updateNotification(sender, threadId, content, score)
            notifySpamResult(messageId, threadId, score)
        }
        return Result.success()
    }

    private fun updateNotification(sender: String, threadId: Long, content: String, score: Float) {
        if (sender.isBlank()) return
        val notificationBody = if (score >= SPAM_THRESHOLD) {
            "已识别为骚扰短信，内容已自动隐藏"
        } else {
            content
        }
        SmsNotificationHelper.showSmsNotification(
            context = applicationContext,
            sender = sender,
            body = notificationBody,
            threadId = threadId,
        )
    }

    private fun notifySpamResult(messageId: Long, threadId: Long, score: Float) {
        val intent = Intent(ACTION_SPAM_DETECTED).apply {
            setPackage(applicationContext.packageName)
            putExtra(KEY_MESSAGE_ID, messageId)
            putExtra(KEY_THREAD_ID, threadId)
            putExtra(KEY_SCORE, score)
        }
        applicationContext.sendBroadcast(intent)
    }
}
