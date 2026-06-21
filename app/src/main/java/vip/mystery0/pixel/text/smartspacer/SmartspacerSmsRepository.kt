package vip.mystery0.pixel.text.smartspacer

import vip.mystery0.pixel.text.data.source.SmartspacerSmsRow
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser

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
) {
    fun getUnreadSmsCount(): Int {
        return telephonyDataSource.getUnreadSmsCountForSmartspacer()
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
    }
}
