package vip.mystery0.pixel.text.mms.outgoing

/**
 * 接收 parser 为兼容旧设备会规范化未知 response-status/version。
 * 发送判定不能把畸形状态规范化成“明确拒绝”，因此先保守验证原始确认头。
 */
internal object MmsSendConfInspector {
    fun responseStatus(bytes: ByteArray): Int? = runCatching {
        var position = 0
        var messageType: Int? = null
        var version: Int? = null
        var status: Int? = null
        var transactionSeen = false
        val seen = mutableSetOf<Int>()
        fun byte(): Int { check(position < bytes.size); return bytes[position++].toInt() and 255 }
        fun skip(count: Int) { check(count >= 0 && count <= bytes.size - position); position += count }
        fun text() {
            if (position < bytes.size && (bytes[position].toInt() and 255) == 127) position++
            var length = 0
            while (true) {
                val value = byte(); if (value == 0) break
                check(value >= 32 && value != 127); length++; check(length <= 4096)
            }
            check(length > 0)
        }
        fun encoded() {
            check(position < bytes.size)
            val start = bytes[position].toInt() and 255
            if (start <= 31) {
                position++
                var length = start
                if (start == 31) {
                    length = 0; var count = 0
                    while (true) { val part = byte(); check(++count <= 5 && length <= 0xFFFFFF); length = (length shl 7) or (part and 127); if (part and 128 == 0) break }
                }
                check(length > 0); skip(length)
            } else text()
        }
        while (position < bytes.size) {
            val header = byte(); check(seen.add(header))
            when (header) {
                0x8C -> messageType = byte()
                0x8D -> version = byte()
                0x92 -> status = byte()
                0x98 -> { text(); transactionSeen = true }
                0x8B, 0x83, 0x9E, 0xB7, 0xB8, 0xB9 -> text()
                0x93, 0xA6 -> encoded()
                0xA5, 0xA7, 0x9C -> byte()
                else -> error("unsupported confirmation header")
            }
        }
        check(messageType == 0x81 && version in 0x90..0x93 && transactionSeen)
        status?.takeIf { it == 0x80 || it in 0x81..0x88 || it in 0xC0..0xC4 || it in 0xE0..0xEB }
    }.getOrNull()
}
