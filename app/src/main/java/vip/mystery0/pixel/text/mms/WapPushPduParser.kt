package vip.mystery0.pixel.text.mms

import vip.mystery0.pixel.text.mms.vendor.pdu.NotificationInd
import vip.mystery0.pixel.text.mms.vendor.pdu.PduParser

data class MmsNotificationInd(
    val transactionId: String,
    val contentLocation: String,
    val from: String?,
    val subject: String?,
    val messageSize: Long,
    val expiry: Long,
)

/** 与正文解析共享锁，保留字符集并将相对过期时间转为绝对时间。 */
object WapPushPduParser {
    fun parse(pdu: ByteArray): MmsNotificationInd? = synchronized(RetrieveConfParser) {
        runCatching {
            val notification = PduParser(pdu, true).parse() as? NotificationInd ?: return@synchronized null
            MmsNotificationInd(
                transactionId = notification.transactionId.toString(Charsets.ISO_8859_1),
                contentLocation = notification.contentLocation.toString(Charsets.ISO_8859_1),
                from = notification.from?.string?.substringBefore("/TYPE="),
                subject = notification.subject?.string,
                messageSize = notification.messageSize,
                expiry = notification.expiry,
            )
        }.getOrNull()
    }
}
