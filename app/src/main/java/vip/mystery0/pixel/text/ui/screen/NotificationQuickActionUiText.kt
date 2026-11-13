package vip.mystery0.pixel.text.ui.screen

import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionConfig
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionType
import vip.mystery0.pixel.text.util.AppStrings

internal fun NotificationQuickActionType.settingTitle(): String {
    val resId = when (this) {
        NotificationQuickActionType.MARK_READ -> R.string.settings_notif_action_type_mark_read
        NotificationQuickActionType.COPY_CODE -> R.string.settings_notif_action_type_copy_code
        NotificationQuickActionType.REPLY -> R.string.settings_notif_action_type_reply
    }
    return AppStrings.get(resId)
}

internal fun List<NotificationQuickActionConfig>.settingsSummary(): String {
    return joinToString(separator = " · ") { it.labelTemplate }
}
