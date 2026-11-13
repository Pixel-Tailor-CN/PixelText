package vip.mystery0.pixel.text.mms.outgoing

import android.app.Activity
import android.telephony.SmsManager
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.mms.vendor.pdu.PduParser
import vip.mystery0.pixel.text.mms.vendor.pdu.SendConf
import vip.mystery0.pixel.text.util.AppStrings

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
            return Outcome(MmsSendState.UNKNOWN, confirmation = "invalid_response", error = AppStrings.get(R.string.sys_mms_result_invalid_response))
        }
        val status = conf.responseStatus
        if (status == 0x80 && ok) return Outcome(MmsSendState.SENT, status,
            conf.messageId?.takeIf { it.size <= 1024 }?.toString(Charsets.ISO_8859_1), "mmsc_accepted")
        if (status == 0x80 || status == 0xC4) return Outcome(MmsSendState.UNKNOWN, status, confirmation = "inconsistent_response",
            error = AppStrings.get(R.string.sys_mms_result_inconsistent_response))
        // 只有有效且事务匹配的明确拒绝可供普通手动重试。
        if (status in 0x81..0x88 || status in 0xC0..0xC3 || status in 0xE0..0xEB) return Outcome(MmsSendState.FAILED, status,
            confirmation = "mmsc_rejected", error = AppStrings.get(R.string.sys_mms_result_mmsc_rejected))
        return Outcome(MmsSendState.UNKNOWN, status, confirmation = "unknown_response", error = AppStrings.get(R.string.sys_mms_result_unknown_response))
    }
    private fun platformError(result: Int): String = when (result) {
        SmsManager.MMS_ERROR_INVALID_APN -> AppStrings.get(R.string.sys_mms_result_error_invalid_apn)
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> AppStrings.get(R.string.sys_mms_result_error_unable_connect)
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> AppStrings.get(R.string.sys_mms_result_error_no_data)
        SmsManager.MMS_ERROR_HTTP_FAILURE -> AppStrings.get(R.string.sys_mms_result_error_http_failure)
        SmsManager.MMS_ERROR_IO_ERROR -> AppStrings.get(R.string.sys_mms_result_error_io_error)
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> AppStrings.get(R.string.sys_mms_result_error_config)
        else -> AppStrings.get(R.string.sys_mms_result_error_generic)
    }
    const val MAX_RESPONSE_BYTES = 64 * 1024
}
