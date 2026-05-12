package vip.mystery0.pixel.text.mms

/**
 * 从 WAP Push PDU 中解析出的 MMS 通知指示（M-Notification.ind）关键字段。
 */
data class MmsNotificationInd(
    val transactionId: String,
    val contentLocation: String,
    val from: String?,
    val subject: String?,
    val messageSize: Long,
    val expiry: Long,
)

/**
 * 最小化的 WAP Push PDU 解析器。
 *
 * 仅解析 M-Notification.ind（MMS 通知指示）中我们需要的字段：
 * - X-Mms-Transaction-ID
 * - X-Mms-Content-Location
 * - From
 * - Subject
 * - X-Mms-Message-Size
 * - X-Mms-Expiry
 *
 * 参考 OMA-WAP-MMS-ENC 规范和 AOSP PduParser 实现。
 */
object WapPushPduParser {

    // MMS PDU header field IDs (OMA-WAP-MMS-ENC Table 11)
    private const val HEADER_CONTENT_LOCATION = 0x83
    private const val HEADER_CONTENT_TYPE = 0x84
    private const val HEADER_FROM = 0x89
    private const val HEADER_MESSAGE_CLASS = 0x8A
    private const val HEADER_MESSAGE_SIZE = 0x8E
    private const val HEADER_MESSAGE_TYPE = 0x8C
    private const val HEADER_MMS_VERSION = 0x8D
    private const val HEADER_SUBJECT = 0x96
    private const val HEADER_TRANSACTION_ID = 0x98
    private const val HEADER_EXPIRY = 0x88

    // Value tokens
    private const val MMS_MESSAGE_TYPE_NOTIFICATION_IND: Byte = 0x82.toByte()
    private const val VALUE_LENGTH_QUOTE = 31
    private const val SHORT_INTEGER_MASK = 0x80

    fun parse(pdu: ByteArray): MmsNotificationInd? {
        if (pdu.isEmpty()) return null

        var pos = 0
        var transactionId: String? = null
        var contentLocation: String? = null
        var from: String? = null
        var subject: String? = null
        var messageSize: Long = 0
        var expiry: Long = 0
        var isNotificationInd = false

        while (pos < pdu.size) {
            val headerField = pdu[pos].toInt() and 0xFF
            pos++

            when (headerField) {
                HEADER_MESSAGE_TYPE -> {
                    if (pos < pdu.size) {
                        isNotificationInd = pdu[pos] == MMS_MESSAGE_TYPE_NOTIFICATION_IND
                        pos++
                    }
                }

                HEADER_TRANSACTION_ID -> {
                    val result = readTextString(pdu, pos)
                    transactionId = result.first
                    pos = result.second
                }

                HEADER_CONTENT_LOCATION -> {
                    val result = readTextString(pdu, pos)
                    contentLocation = result.first
                    pos = result.second
                }

                HEADER_FROM -> {
                    val result = readFromField(pdu, pos)
                    from = result.first
                    pos = result.second
                }

                HEADER_SUBJECT -> {
                    val result = readEncodedString(pdu, pos)
                    subject = result.first
                    pos = result.second
                }

                HEADER_MESSAGE_SIZE -> {
                    val result = readLongInteger(pdu, pos)
                    messageSize = result.first
                    pos = result.second
                }

                HEADER_EXPIRY -> {
                    val result = readExpiryValue(pdu, pos)
                    expiry = result.first
                    pos = result.second
                }

                HEADER_MMS_VERSION -> {
                    // 1 byte short-integer
                    pos++
                }

                HEADER_MESSAGE_CLASS -> {
                    // 可能是 token 或 text-string
                    if (pos < pdu.size) {
                        val b = pdu[pos].toInt() and 0xFF
                        if (b >= 0x80) {
                            pos++ // token value
                        } else {
                            val result = readTextString(pdu, pos)
                            pos = result.second
                        }
                    }
                }

                HEADER_CONTENT_TYPE -> {
                    // 跳过 content-type（notification-ind 中通常是 application/vnd.wap.mms-message）
                    val result = readTextString(pdu, pos)
                    pos = result.second
                }

                else -> {
                    // 未知 header：尝试跳过
                    pos = skipUnknownHeader(pdu, pos, headerField)
                }
            }
        }

        if (!isNotificationInd || transactionId == null || contentLocation == null) {
            return null
        }

        return MmsNotificationInd(
            transactionId = transactionId,
            contentLocation = contentLocation,
            from = from,
            subject = subject,
            messageSize = messageSize,
            expiry = expiry,
        )
    }

    private fun readTextString(data: ByteArray, startPos: Int): Pair<String, Int> {
        var pos = startPos
        // 跳过可选的 quote (0x7F)
        if (pos < data.size && data[pos].toInt() and 0xFF == 0x7F) {
            pos++
        }
        val sb = StringBuilder()
        while (pos < data.size && data[pos] != 0.toByte()) {
            sb.append(data[pos].toInt().toChar())
            pos++
        }
        pos++ // 跳过 null terminator
        return sb.toString() to pos
    }

    private fun readFromField(data: ByteArray, startPos: Int): Pair<String?, Int> {
        var pos = startPos
        // Value-length
        val lenResult = readValueLength(data, pos)
        val valueLen = lenResult.first
        pos = lenResult.second

        if (valueLen <= 0 || pos >= data.size) return null to pos

        val endPos = pos + valueLen
        val addressPresentToken = data[pos].toInt() and 0xFF
        pos++

        if (addressPresentToken == 0x80) {
            // Address-present-token: 有地址
            val result = readEncodedString(data, pos)
            return result.first to endPos.coerceAtMost(data.size)
        }
        // Insert-address-token (0x81): 由 MMSC 填充，无地址
        return null to endPos.coerceAtMost(data.size)
    }

    private fun readEncodedString(data: ByteArray, startPos: Int): Pair<String, Int> {
        var pos = startPos
        if (pos >= data.size) return "" to pos

        val firstByte = data[pos].toInt() and 0xFF
        if (firstByte < VALUE_LENGTH_QUOTE) {
            // text-string
            return readTextString(data, pos)
        }

        // Value-length Charset Text-string
        val lenResult = readValueLength(data, pos)
        val valueLen = lenResult.first
        pos = lenResult.second
        val endPos = pos + valueLen

        // 跳过 charset (short-integer 或 long-integer)
        if (pos < data.size) {
            val charsetByte = data[pos].toInt() and 0xFF
            if (charsetByte and SHORT_INTEGER_MASK != 0) {
                pos++ // short-integer
            } else {
                pos += charsetByte + 1 // long-integer: length + data
            }
        }

        val sb = StringBuilder()
        while (pos < endPos && pos < data.size && data[pos] != 0.toByte()) {
            sb.append(data[pos].toInt().toChar())
            pos++
        }
        return sb.toString() to endPos.coerceAtMost(data.size)
    }

    private fun readLongInteger(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var pos = startPos
        if (pos >= data.size) return 0L to pos

        val firstByte = data[pos].toInt() and 0xFF
        if (firstByte and SHORT_INTEGER_MASK != 0) {
            // Short-integer
            return (firstByte and 0x7F).toLong() to (pos + 1)
        }

        // Long-integer: first byte = length of following octets
        val length = firstByte
        pos++
        var value = 0L
        for (i in 0 until length) {
            if (pos >= data.size) break
            value = (value shl 8) or (data[pos].toInt() and 0xFF).toLong()
            pos++
        }
        return value to pos
    }

    private fun readExpiryValue(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var pos = startPos
        val lenResult = readValueLength(data, pos)
        pos = lenResult.second
        val endPos = pos + lenResult.first

        if (pos >= data.size) return 0L to endPos.coerceAtMost(data.size)

        // token: 0x80 = absolute, 0x81 = relative
        pos++

        val intResult = readLongInteger(data, pos)
        return intResult.first to endPos.coerceAtMost(data.size)
    }

    private fun readValueLength(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var pos = startPos
        if (pos >= data.size) return 0 to pos

        val firstByte = data[pos].toInt() and 0xFF
        pos++

        if (firstByte < VALUE_LENGTH_QUOTE) {
            return firstByte to pos
        }
        if (firstByte == VALUE_LENGTH_QUOTE) {
            // 后面跟一个 uintvar
            return readUintVar(data, pos)
        }
        // 不应该到这里，但兜底
        return 0 to pos
    }

    private fun readUintVar(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var pos = startPos
        var value = 0
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            pos++
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) break
        }
        return value to pos
    }

    private fun skipUnknownHeader(data: ByteArray, startPos: Int, headerField: Int): Int {
        var pos = startPos
        if (pos >= data.size) return pos

        val firstByte = data[pos].toInt() and 0xFF

        // 如果是 short-integer (高位为1)，跳过 1 字节
        if (firstByte and SHORT_INTEGER_MASK != 0) {
            return pos + 1
        }

        // 如果是 text-string (以可打印字符开头)
        if (firstByte in 32..127) {
            val result = readTextString(data, pos)
            return result.second
        }

        // 否则尝试按 value-length 跳过
        val lenResult = readValueLength(data, pos)
        return (lenResult.second + lenResult.first).coerceAtMost(data.size)
    }
}
