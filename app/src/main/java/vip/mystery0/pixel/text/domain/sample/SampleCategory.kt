package vip.mystery0.pixel.text.domain.sample

import androidx.annotation.StringRes
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.util.AppStrings

enum class SampleCategory(
    val value: String,
    @param:StringRes val labelRes: Int,
) {
    VERIFICATION_CODE("verification_code", R.string.domain_sample_category_verification_code),
    BANK_TRANSACTION("bank_transaction", R.string.domain_sample_category_bank_transaction),
    PHONE_RECHARGE("phone_recharge", R.string.domain_sample_category_phone_recharge),
    EXPRESS_DELIVERY("express_delivery", R.string.domain_sample_category_express_delivery),
    TICKET("ticket", R.string.domain_sample_category_ticket),
    MISSED_CALL("missed_call", R.string.domain_sample_category_missed_call),
    DATA_USAGE("data_usage", R.string.domain_sample_category_data_usage),
    SPAM("spam", R.string.domain_sample_category_spam),
    NORMAL("normal", R.string.domain_sample_category_normal),
    ;

    val label: String get() = AppStrings.get(labelRes)

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
