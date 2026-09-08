package vip.mystery0.pixel.text.mms

import vip.mystery0.pixel.text.mms.vendor.pdu.*

sealed interface MmsIncomingEvent {
    data class Notification(val value: MmsNotificationInd) : MmsIncomingEvent
    data class Report(val type: Int, val messageId: ByteArray, val date: Long,
        val from: String?, val to: List<String>, val status: Int, val version: Int) : MmsIncomingEvent
    data class Unsupported(val type: Int) : MmsIncomingEvent
    data object Invalid : MmsIncomingEvent
}

data class MmsNotificationInd(
    val transaction: ByteArray,
    val contentLocation: String,
    val from: String?,
    val subject: String?,
    val messageSize: Long,
    val expiryValue: Long,
    val expiryRelative: Boolean,
    val version: Int,
) {
    val transactionId get() = transaction.toString(Charsets.ISO_8859_1)
    fun deadline(received: Long): Long = if (expiryRelative) {
        if (expiryValue > Long.MAX_VALUE - received / 1000) Long.MAX_VALUE else received / 1000 + expiryValue
    } else expiryValue
}

/** 与正文解析共用锁，只解析头部事件，不把报告伪装成短信。 */
object WapPushPduParser {
    fun parse(pdu: ByteArray): MmsIncomingEvent = synchronized(RetrieveConfParser) {
        if (pdu.size > 1024 * 1024) return@synchronized MmsIncomingEvent.Invalid
        try {
            val parser = PduParser(pdu, true)
            when (val message = parser.parse()) {
                is NotificationInd -> if (parser.expiryValue < 0) MmsIncomingEvent.Invalid else MmsIncomingEvent.Notification(MmsNotificationInd(
                    message.transactionId, message.contentLocation.toString(Charsets.ISO_8859_1),
                    message.from?.string, message.subject?.string, message.messageSize,
                    parser.expiryValue, parser.isExpiryRelative, message.mmsVersion,
                ))
                is DeliveryInd -> MmsIncomingEvent.Report(message.messageType, message.messageId,
                    message.date, null, message.to.orEmpty().map { it.string }, message.status, message.mmsVersion)
                is ReadOrigInd -> MmsIncomingEvent.Report(message.messageType, message.messageId,
                    message.date, message.from?.string, message.to.orEmpty().map { it.string },
                    message.readStatus, message.mmsVersion)
                null -> MmsIncomingEvent.Invalid
                else -> MmsIncomingEvent.Unsupported(message.messageType)
            }
        } catch (_: RuntimeException) { MmsIncomingEvent.Invalid }
    }
}
