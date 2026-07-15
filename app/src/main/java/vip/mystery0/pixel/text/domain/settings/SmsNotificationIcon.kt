package vip.mystery0.pixel.text.domain.settings

import androidx.annotation.DrawableRes
import vip.mystery0.pixel.text.R

/**
 * 应用内置的短信通知小图标。
 *
 * [storageId] 会持久化到本地设置中，已经发布的值不应修改。
 */
enum class SmsNotificationIcon(
    val storageId: String,
    val displayName: String,
    @param:DrawableRes val drawableRes: Int,
) {
    DOUBLE_BUBBLE(
        storageId = "double_bubble",
        displayName = "双气泡",
        drawableRes = R.drawable.ic_notification_sms,
    ),
    SINGLE_BUBBLE(
        storageId = "single_bubble",
        displayName = "单气泡",
        drawableRes = R.drawable.ic_sms_notification_single_bubble,
    ),
    ENVELOPE(
        storageId = "envelope",
        displayName = "信封",
        drawableRes = R.drawable.ic_sms_notification_envelope,
    ),
    TEXT_BUBBLE(
        storageId = "text_bubble",
        displayName = "文本气泡",
        drawableRes = R.drawable.ic_sms_notification_text_bubble,
    );

    companion object {
        val default: SmsNotificationIcon = DOUBLE_BUBBLE

        fun fromId(storageId: String?): SmsNotificationIcon =
            entries.firstOrNull { it.storageId == storageId } ?: default
    }
}
