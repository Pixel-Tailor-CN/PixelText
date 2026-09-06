package vip.mystery0.pixel.text.mms

import vip.mystery0.pixel.text.mms.vendor.pdu.PduParser
import vip.mystery0.pixel.text.mms.vendor.pdu.RetrieveConf

/** 使用应用内的公开源码解析，串行保护上游解析器的共享参数。 */
object RetrieveConfParser {
    @Synchronized
    fun parse(bytes: ByteArray): RetrieveConf {
        val message = PduParser(bytes, true).parse() as? RetrieveConf
            ?: throw IllegalArgumentException("invalid retrieve confirmation")
        check(message.retrieveStatus == 0 || message.retrieveStatus == 128) {
            "mms retrieval rejected"
        }
        check(message.body != null) { "mms body missing" }
        return message
    }
}
