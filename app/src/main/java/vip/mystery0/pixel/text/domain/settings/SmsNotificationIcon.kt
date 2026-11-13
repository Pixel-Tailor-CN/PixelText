package vip.mystery0.pixel.text.domain.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.util.AppStrings

/**
 * 应用内置的短信通知小图标。
 *
 * [storageId] 会持久化到本地设置中，已经发布的值不应修改。
 */
enum class SmsNotificationIcon(
    val storageId: String,
    @param:StringRes val displayNameRes: Int,
    @param:DrawableRes val drawableRes: Int,
) {
    DOUBLE_BUBBLE(
        storageId = "double_bubble",
        displayNameRes = R.string.domain_sms_notification_icon_double_bubble,
        drawableRes = R.drawable.ic_notification_sms,
    ),
    SINGLE_BUBBLE(
        storageId = "single_bubble",
        displayNameRes = R.string.domain_sms_notification_icon_single_bubble,
        drawableRes = R.drawable.ic_sms_notification_single_bubble,
    ),
    ENVELOPE(
        storageId = "envelope",
        displayNameRes = R.string.domain_sms_notification_icon_envelope,
        drawableRes = R.drawable.ic_sms_notification_envelope,
    ),
    TEXT_BUBBLE(
        storageId = "text_bubble",
        displayNameRes = R.string.domain_sms_notification_icon_text_bubble,
        drawableRes = R.drawable.ic_sms_notification_text_bubble,
    );

    val displayName: String get() = AppStrings.get(displayNameRes)

    companion object {
        val default: SmsNotificationIcon = DOUBLE_BUBBLE

        fun fromId(storageId: String?): SmsNotificationIcon =
            entries.firstOrNull { it.storageId == storageId } ?: default
    }
}
