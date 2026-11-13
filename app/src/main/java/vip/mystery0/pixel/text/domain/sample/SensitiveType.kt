package vip.mystery0.pixel.text.domain.sample

import androidx.annotation.StringRes
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.util.AppStrings

enum class SensitiveType(@param:StringRes val labelRes: Int) {
    NAME(R.string.domain_sensitive_type_name),
    PHONE(R.string.domain_sensitive_type_phone),
    ID_CARD(R.string.domain_sensitive_type_id_card),
    ADDRESS(R.string.domain_sensitive_type_address),
    BANK_CARD(R.string.domain_sensitive_type_bank_card),
    ORDER_ID(R.string.domain_sensitive_type_order_id),
    VERIFICATION_CODE(R.string.domain_sensitive_type_verification_code),
    AMOUNT(R.string.domain_sensitive_type_amount),
    DATE(R.string.domain_sensitive_type_date),
    TIME(R.string.domain_sensitive_type_time),
    DATE_TIME(R.string.domain_sensitive_type_date_time);

    val label: String get() = AppStrings.get(labelRes)
}
