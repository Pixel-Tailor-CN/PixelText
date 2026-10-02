package vip.mystery0.pixel.text.mms.outgoing

import android.app.Activity
import android.telephony.SmsManager
import vip.mystery0.pixel.text.mms.vendor.pdu.PduParser
import vip.mystery0.pixel.text.mms.vendor.pdu.SendConf

/** HTTP 成功、MMSC 接受和对方送达不是同一件事。 */
object MmsSendResult {
    data class Outcome(val state: MmsSendState, val responseStatus: Int? = null, val messageId: String? = null,
        val confirmation: String, val error: String? = null)
    fun interpret(result: Int, response: ByteArray?, transactionId: String): Outcome {
        val ok = result == Activity.RESULT_OK
        if (response == null) return if (ok) Outcome(MmsSendState.SENT, confirmation = "platform_only")
            else Outcome(MmsSendState.UNKNOWN, confirmation = "unconfirmed", error = platformError(result))
        val rawStatus = if (response.size in 1..MAX_RESPONSE_BYTES) MmsSendConfInspector.responseStatus(response) else null
        val conf = if (rawStatus != null) runCatching { PduParser(response, true).parse() as? SendConf }.getOrNull() else null
        if (conf == null || conf.responseStatus != rawStatus || !conf.transactionId.contentEquals(transactionId.toByteArray(Charsets.US_ASCII))) {
            return Outcome(MmsSendState.UNKNOWN, confirmation = "invalid_response", error = "彩信确认异常，可能已发出，请勿直接重发")
        }
        val status = conf.responseStatus
        if (status == 0x80 && ok) return Outcome(MmsSendState.SENT, status,
            conf.messageId?.takeIf { it.size <= 1024 }?.toString(Charsets.ISO_8859_1), "mmsc_accepted")
        if (status == 0x80 || status == 0xC4) return Outcome(MmsSendState.UNKNOWN, status, confirmation = "inconsistent_response",
            error = "彩信结果不完整，可能已发出，请勿直接重发")
        // 只有有效且事务匹配的明确拒绝可供普通手动重试。
        if (status in 0x81..0x88 || status in 0xC0..0xC3 || status in 0xE0..0xEB) return Outcome(MmsSendState.FAILED, status,
            confirmation = "mmsc_rejected", error = "运营商拒绝了这条彩信，请检查内容和套餐后手动重试")
        return Outcome(MmsSendState.UNKNOWN, status, confirmation = "unknown_response", error = "未能确认彩信结果，可能已发出")
    }
    private fun platformError(result: Int): String = when (result) {
        SmsManager.MMS_ERROR_INVALID_APN -> "彩信 APN 不可用，发送结果仍需确认"
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "无法连接彩信网络，发送结果仍需确认"
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "彩信数据网络不可用，发送结果仍需确认"
        SmsManager.MMS_ERROR_HTTP_FAILURE -> "彩信服务器通信失败，发送结果仍需确认"
        SmsManager.MMS_ERROR_IO_ERROR -> "彩信传输中断，发送结果仍需确认"
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "运营商彩信配置异常，发送结果仍需确认"
        else -> "系统未确认彩信成功，可能已发出，请勿直接重发"
    }
    const val MAX_RESPONSE_BYTES = 64 * 1024
}
