package vip.mystery0.pixel.text.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.KeywordSpamMessage
import vip.mystery0.pixel.text.domain.spam.KeywordSpamRepository
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration

class KeywordSpamRebuildWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val telephonyDataSource: TelephonyDataSource by inject()
    private val keywordSpamRepository: KeywordSpamRepository by inject()

    override suspend fun doWork(): Result = runCatching {
        val messages = telephonyDataSource.getSmsMessagesForSpamScan().map { row ->
            KeywordSpamMessage(
                messageId = row.messageId,
                threadId = row.threadId,
                content = row.content,
            )
        }
        val matchedCount = keywordSpamRepository.rebuildMatches(messages)
        SmartspacerIntegration.notifyChanged(applicationContext)
        Log.d(TAG, "keyword spam rebuild complete total=${messages.size} matched=$matchedCount")
        Result.success(workDataOf(KEY_MATCHED_COUNT to matchedCount))
    }.getOrElse { error ->
        Log.e(TAG, "keyword spam rebuild failed", error)
        Result.failure()
    }

    companion object {
        const val KEY_MATCHED_COUNT = "matched_count"
        private const val TAG = "KeywordSpamRebuild"
    }
}
