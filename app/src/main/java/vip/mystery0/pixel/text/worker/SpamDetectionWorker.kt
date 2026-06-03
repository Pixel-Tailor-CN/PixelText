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
import vip.mystery0.pixel.text.BuildConfig
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

        val ACTION_SPAM_DETECTED = "${BuildConfig.APPLICATION_ID}.action.SPAM_DETECTED"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_THREAD_ID = "thread_id"
        const val KEY_SENDER = "sender"
        const val KEY_CONTENT = "content"
        const val KEY_SCORE = "score"
        private const val KEY_DEFER_NOTIFICATION = "defer_notification"
        private const val KEY_MESSAGE_URI = "message_uri"

        fun schedule(
            context: Context,
            messageId: Long,
            threadId: Long,
            sender: String,
            content: String,
            deferNotification: Boolean = false,
            messageUri: String? = null
        ) {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_THREAD_ID to threadId,
                KEY_SENDER to sender,
                KEY_CONTENT to content,
                KEY_DEFER_NOTIFICATION to deferNotification,
                KEY_MESSAGE_URI to messageUri.orEmpty()
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
        val deferNotification = inputData.getBoolean(KEY_DEFER_NOTIFICATION, false)
        val messageUri = inputData.getString(KEY_MESSAGE_URI).orEmpty().takeIf { it.isNotBlank() }
        if (messageId < 0 || threadId < 0) return Result.failure()

        if (!spamRepository.isEnabled()) {
            if (deferNotification) {
                showOriginalNotification(sender, threadId, content, messageUri)
            }
            return Result.success()
        }

        val score = runCatching {
            try {
                classificationSemaphore.withPermit {
                    spamClassifier.classify(content)
                }
            } finally {
                spamClassifier.close()
            }
        }.getOrElse { e ->
            Log.e(TAG, "failed to classify spam message_id=$messageId", e)
            if (deferNotification) {
                showOriginalNotification(sender, threadId, content, messageUri)
                return Result.success()
            }
            return Result.failure()
        }

        if (score < 0f) {
            if (deferNotification) {
                showOriginalNotification(sender, threadId, content, messageUri)
            }
            return Result.success()
        }

        if (score >= 0f) {
            spamRepository.save(messageId, threadId, score)
            Log.d(TAG, "spam score message_id=$messageId score=$score")
            updateNotification(
                sender = sender,
                threadId = threadId,
                content = content,
                score = score,
                deferNotification = deferNotification,
                messageUri = messageUri
            )
            notifySpamResult(messageId, threadId, score)
        }
        return Result.success()
    }

    private fun updateNotification(
        sender: String,
        threadId: Long,
        content: String,
        score: Float,
        deferNotification: Boolean,
        messageUri: String?
    ) {
        if (sender.isBlank()) return
        if (deferNotification && score >= SPAM_THRESHOLD) return
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
            messageUri = messageUri
        )
    }

    private fun showOriginalNotification(
        sender: String,
        threadId: Long,
        content: String,
        messageUri: String?
    ) {
        if (sender.isBlank()) return
        SmsNotificationHelper.showSmsNotification(
            context = applicationContext,
            sender = sender,
            body = content,
            threadId = threadId,
            messageUri = messageUri
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
