package vip.mystery0.pixel.text.worker

import androidx.core.net.toUri
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
import vip.mystery0.pixel.text.data.repository.SenderProfileRepository
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.settings.SpamAutoAction
import vip.mystery0.pixel.text.domain.spam.KeywordSpamRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.notification.SmsNotificationHelper
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration

class SpamDetectionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        private const val TAG = "SpamDetectionWorker"
        private const val MAX_CONCURRENT_CLASSIFICATIONS = 10
        private const val SPAM_THRESHOLD = 0.7f
        private val classificationSemaphore = Semaphore(MAX_CONCURRENT_CLASSIFICATIONS)

        const val ACTION_SPAM_DETECTED = "${BuildConfig.APPLICATION_ID}.action.SPAM_DETECTED"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_THREAD_ID = "thread_id"
        const val KEY_CONTENT = "content"
        private const val KEY_MESSAGE_DATE = "message_date"
        const val KEY_SCORE = "score"
        private const val KEY_DEFER_NOTIFICATION = "defer_notification"
        private const val KEY_MESSAGE_URI = "message_uri"

        fun schedule(
            context: Context,
            messageId: Long,
            threadId: Long,
            timestamp: Long,
            deferNotification: Boolean = false,
            messageUri: String? = null
        ) {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_THREAD_ID to threadId,
                KEY_MESSAGE_DATE to timestamp,
                KEY_DEFER_NOTIFICATION to deferNotification,
                KEY_MESSAGE_URI to messageUri.orEmpty()
            )
            WorkManager.getInstance(context)
                .enqueue(
                    OneTimeWorkRequestBuilder<SpamDetectionWorker>().setInputData(data).build()
                )
        }
    }

    private val restoreSafety: vip.mystery0.pixel.text.data.backup.RestoreSafetyCoordinator by inject()
    private val spamRepository: SpamRepository by inject()
    private val whitelist: vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository by inject()
    private val spamClassifierFactory: vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory by inject()
    private val learning: vip.mystery0.pixel.text.domain.spam.learning.PersonalLearningRepository by inject()
    private val senderProfileRepository: SenderProfileRepository by inject()
    private val contactDataSource: ContactDataSource by inject()
    private val keywordSpamRepository: KeywordSpamRepository by inject()
    private val settingsRepository: AppSettingsRepository by inject()
    private val messageRepository: MessageRepository by inject()

    override suspend fun doWork(): Result {
        spamRepository.ensureReady()
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        if (messageId < 0) return Result.failure()
        val messageUri = android.content.ContentUris.withAppendedId(android.provider.Telephony.Sms.CONTENT_URI, messageId).toString()
        // 正文保留在 Provider；既避免 Data 的 10KB 限制，也不使用已删除或替换的旧正文。
        val row = applicationContext.contentResolver.query(
            messageUri.toUri(), arrayOf("thread_id", "address", "body", "date", "type"), null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(4) != android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX) null
            else IncomingSms(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getLong(3))
        } ?: return Result.success()
        if (row.threadId != inputData.getLong(KEY_THREAD_ID, -1L)) return Result.success()
        val expectedDate = inputData.getLong(KEY_MESSAGE_DATE, -1L)
        if (expectedDate >= 0 && row.date != expectedDate) return Result.success()
        // 兼容升级前排队的任务，但不把其快照当作当前消息。
        inputData.getString(KEY_CONTENT)?.let { if (it != row.content) return Result.success() }
        val threadId = row.threadId
        val sender = row.sender
        val content = row.content
        val deferNotification = inputData.getBoolean(KEY_DEFER_NOTIFICATION, false)

        val allowedAtStart = whitelist.isAllowed(messageId)
        val keywordMatched = !allowedAtStart && runCatching {
            keywordSpamRepository.updateMessageMatch(messageId)
        }.getOrElse { error ->
            Log.e(TAG, "keyword spam match failed message_id=$messageId", error)
            false
        }

        val prediction = if (!allowedAtStart && spamRepository.isEnabled() && content.isNotBlank()) {
            runCatching {
                classificationSemaphore.withPermit {
                    spamClassifierFactory.create().use { it.predict(content) }
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "failed to classify spam message_id=$messageId", error)
                null
            }
        } else {
            null
        }


        // 模型执行期间可能新增白名单。最终自动操作和通知与规则修改串行化，不能使用过期判定。
        // save 自行获取白名单锁，必须位于后面的 withMessageDecision 临界区之外。
        val key = vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey.create("SMS", messageId, sender, row.date, content)
        val messageSource = vip.mystery0.pixel.text.data.source.LearningMessageSource(applicationContext.contentResolver)
        var stale = false
        restoreSafety.liveMessageEffects(messageId) { allowAutoAction ->
            if (prediction != null && spamRepository.isEnabled() && !spamRepository.savePrediction(messageId, prediction, key)) {
                stale = true
                return@liveMessageEffects
            }
            learning.withDecision {
            if (prediction != null && !spamRepository.isPredictionCurrent(prediction)) {
                stale = true
                return@withDecision
            }
            if (messageSource.keys(listOf(messageId))[messageId] != key) return@withDecision
            // 重新按当前规则匹配，避免推理前的关键词缓存直接驱动自动操作。
            keywordSpamRepository.updateMessageMatch(messageId)
            val score = spamRepository.scoreForKey(key)
            whitelist.withMessageDecision(messageId) { allowed ->
                if (messageSource.keys(listOf(messageId))[messageId] != key) return@withMessageDecision
                val isSpam = !allowed && (score != null && score >= SPAM_THRESHOLD)
                if (allowAutoAction && (prediction == null || spamRepository.isEnabled())) applySpamAutoAction(
                    messageId = messageId,
                    isSpam = isSpam,
                    deferNotification = deferNotification,
                )
                updateNotification(
                    sender = sender,
                    threadId = threadId,
                    content = content,
                    isSpam = isSpam,
                    deferNotification = deferNotification,
                    messageUri = messageUri,
                )
                if (allowed || keywordMatched || (score != null && score >= 0f)) {
                    notifySpamResult(
                        messageId = messageId,
                        threadId = threadId,
                        score = if (allowed) 0f else score ?: -1f,
                    )
                    SmartspacerIntegration.notifyChanged(applicationContext)
                }
            }
            }
        }
        return if (stale) Result.retry() else Result.success()
    }

    private suspend fun applySpamAutoAction(
        messageId: Long,
        isSpam: Boolean,
        deferNotification: Boolean,
    ) {
        if (!isSpam) return
        val action = effectiveSpamAutoAction(deferNotification)
        when (action) {
            SpamAutoAction.NONE -> Unit
            SpamAutoAction.MARK_READ -> {
                val updatedCount = messageRepository.markMessagesAsRead(setOf(messageId))
                Log.d(
                    TAG,
                    "spam auto action mark_read message_id=$messageId updated=$updatedCount"
                )
            }

            SpamAutoAction.DELETE -> {
                val deletedCount = messageRepository.deleteMessages(setOf(messageId))
                Log.d(
                    TAG,
                    "spam auto action delete message_id=$messageId deleted=$deletedCount"
                )
            }
        }
    }

    private fun effectiveSpamAutoAction(deferNotification: Boolean): SpamAutoAction {
        if (!deferNotification) return SpamAutoAction.NONE
        if (!settingsRepository.isMuteSpamNotificationsEnabled()) return SpamAutoAction.NONE
        return settingsRepository.getSpamAutoAction()
    }

    private suspend fun updateNotification(
        sender: String,
        threadId: Long,
        content: String,
        isSpam: Boolean,
        deferNotification: Boolean,
        messageUri: String?
    ) {
        if (sender.isBlank()) return
        if (deferNotification && isSpam) return
        val notificationBody = if (isSpam) {
            "已识别为骚扰短信，内容已自动隐藏"
        } else {
            content
        }
        val profile = senderProfileRepository.findByNumber(sender)
        SmsNotificationHelper.showSmsNotification(
            context = applicationContext,
            sender = sender,
            body = notificationBody,
            threadId = threadId,
            messageUri = messageUri,
            displaySender = contactDataSource.getDisplayName(sender)
                ?: profile?.displayName
                ?: sender,
            avatarPath = profile?.avatarPath,
            updateExistingOnly = !deferNotification,
            isSpam = isSpam,
        )
    }

    private data class IncomingSms(val threadId: Long, val sender: String, val content: String, val date: Long)

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
