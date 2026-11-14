package vip.mystery0.pixel.text.domain.settings

import androidx.annotation.StringRes
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.util.AppStrings

enum class AppDisplayName(val storageValue: String, @param:StringRes val labelRes: Int) {
    CHISU_FULL("chisu_full", R.string.domain_app_display_name_chisu_full),
    SMS_FULL("sms_full", R.string.domain_app_display_name_sms_full),
    JIANXUN_FULL("jianxun_full", R.string.domain_app_display_name_jianxun_full),
    PIXEL_TEXT("pixel_text", R.string.domain_app_display_name_pixel_text),
    CHISU("chisu", R.string.domain_app_display_name_chisu),
    SMS("sms", R.string.domain_app_display_name_sms),
    MESSAGE("message", R.string.domain_app_display_name_message),
    PIXEL_MESSAGE("pixel_message", R.string.domain_app_display_name_pixel_message);

    val label: String get() = AppStrings.get(labelRes)
}

enum class AppIconStyle(val storageValue: String, @param:StringRes val labelRes: Int) {
    VIOLET("violet", R.string.domain_app_icon_style_violet),
    ORIGIN("origin", R.string.domain_app_icon_style_origin),
    ZERO("zero", R.string.domain_app_icon_style_zero),
    MESSAGES("messages", R.string.domain_app_icon_style_messages),
    MESSAGE_NEW("message_new", R.string.domain_app_icon_style_message_new),
    CLASSIC("classic", R.string.domain_app_icon_style_classic);

    val label: String get() = AppStrings.get(labelRes)
}

data class AppIdentity(
    val displayName: AppDisplayName = AppDisplayName.PIXEL_TEXT,
    val iconStyle: AppIconStyle = AppIconStyle.CLASSIC,
)
