package vip.mystery0.pixel.text.domain.sample

import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult

enum class SampleCategory(
    val value: String,
    val label: String,
) {
    VERIFICATION_CODE("verification_code", "验证码"),
    BANK_TRANSACTION("bank_transaction", "银行动账"),
    PHONE_RECHARGE("phone_recharge", "话费充值"),
    EXPRESS_DELIVERY("express_delivery", "快递通知"),
    TICKET("ticket", "票务出行"),
    MISSED_CALL("missed_call", "来电提醒"),
    DATA_USAGE("data_usage", "流量提醒"),
    SPAM("spam", "垃圾短信"),
    NORMAL("normal", "普通短信"),
    ;

    companion object {
        fun fromValue(value: String): SampleCategory? = entries.firstOrNull { it.value == value }
    }
}

fun MessageModel.sampleCategory(): SampleCategory {
    if (spamScore >= SPAM_THRESHOLD) return SampleCategory.SPAM
    return when (parsedResult) {
        is ParsedResult.VerificationCode -> SampleCategory.VERIFICATION_CODE
        is ParsedResult.BankTransaction -> SampleCategory.BANK_TRANSACTION
        is ParsedResult.PhoneRecharge -> SampleCategory.PHONE_RECHARGE
        is ParsedResult.ExpressDelivery -> SampleCategory.EXPRESS_DELIVERY
        is ParsedResult.Ticket -> SampleCategory.TICKET
        is ParsedResult.MissedCall -> SampleCategory.MISSED_CALL
        is ParsedResult.DataUsage -> SampleCategory.DATA_USAGE
        is ParsedResult.Dynamic,
        ParsedResult.None -> SampleCategory.NORMAL
    }
}

private const val SPAM_THRESHOLD = 0.7f
