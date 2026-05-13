package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.domain.spam.SpamClassifier
import vip.mystery0.pixel.text.domain.spam.SpamRepository

class SpamDetectionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        private const val TAG = "SpamDetectionWorker"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_THREAD_ID = "thread_id"
        const val KEY_CONTENT = "content"

        fun schedule(context: Context, messageId: Long, threadId: Long, content: String) {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_THREAD_ID to threadId,
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
        val content = inputData.getString(KEY_CONTENT) ?: return Result.failure()
        if (messageId < 0 || threadId < 0) return Result.failure()

        if (!spamRepository.isEnabled()) return Result.success()

        val score = spamClassifier.classify(content)
        if (score >= 0f) {
            spamRepository.save(messageId, threadId, score)
            Log.d(TAG, "消息 $messageId 骚扰概率: $score")
        }
        return Result.success()
    }
}
