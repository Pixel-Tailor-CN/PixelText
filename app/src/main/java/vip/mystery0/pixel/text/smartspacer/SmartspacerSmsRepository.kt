package vip.mystery0.pixel.text.smartspacer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.source.SmartspacerSmsRow
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.spam.SpamRepository

data class SmartspacerVerificationCode(
    val messageId: Long,
    val threadId: Long,
    val sender: String,
    val signature: String?,
    val code: String,
    val timestamp: Long,
)

class SmartspacerSmsRepository(
    private val telephonyDataSource: TelephonyDataSource,
    private val messageParser: MessageParser,
    private val settingsRepository: UnreadSmsComplicationSettingsRepository,
    private val spamRepository: SpamRepository,
    archiveDatabase: ConversationArchiveDatabase,
) {
    private val archiveDao = archiveDatabase.archivedConversationDao()

    fun getUnreadSmsCount(smartspacerId: String): Int {
        val settings = settingsRepository.getSettings(smartspacerId)
        if (!settings.hasEnabledCategory()) return 0

        val unreadMessages = telephonyDataSource.getUnreadSmsMessagesForSmartspacer()
        if (unreadMessages.isEmpty()) return 0

        if (settings.includeNormalMessages &&
            settings.includeSpamMessages &&
            settings.includeArchivedMessages
        ) {
            return unreadMessages.size
        }

        return runBlocking(Dispatchers.IO) {
            val archivedThreadIds =
                if (settings.includeArchivedMessages || settings.includeNormalMessages) {
                    archiveDao.getArchivedThreadIds().toSet()
                } else {
                    emptySet()
                }
            val spamMessageIds =
                if (settings.includeSpamMessages || settings.includeNormalMessages) {
                    spamRepository.getSpamMessageIds(
                        unreadMessages.map { it.id },
                        SPAM_THRESHOLD
                    )
                } else {
                    emptySet()
                }

            unreadMessages.count { row ->
                val isArchived = row.threadId in archivedThreadIds
                val isSpam = row.id in spamMessageIds
                val isNormal = !isArchived && !isSpam
                (settings.includeArchivedMessages && isArchived) ||
                    (settings.includeSpamMessages && isSpam) ||
                    (settings.includeNormalMessages && isNormal)
            }
        }
    }

    fun getLatestUnreadVerificationCode(): SmartspacerVerificationCode? {
        return telephonyDataSource.getRecentSmsMessagesForSmartspacer(RECENT_SMS_LIMIT)
            .asSequence()
            .filterNot(SmartspacerSmsRow::read)
            .mapNotNull { row ->
                val result = messageParser.parse(row.address, row.body)
                val verificationCode = result as? ParsedResult.VerificationCode
                    ?: return@mapNotNull null
                val code = verificationCode.code.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                SmartspacerVerificationCode(
                    messageId = row.id,
                    threadId = row.threadId,
                    sender = row.address,
                    signature = verificationCode.signature?.takeIf(String::isNotBlank),
                    code = code,
                    timestamp = row.date,
                )
            }
            .firstOrNull()
    }

    fun markMessageRead(messageId: Long) {
        telephonyDataSource.markMessagesAsRead(setOf(messageId))
    }

    companion object {
        private const val RECENT_SMS_LIMIT = 50
        private const val SPAM_THRESHOLD = 0.7f
    }
}
