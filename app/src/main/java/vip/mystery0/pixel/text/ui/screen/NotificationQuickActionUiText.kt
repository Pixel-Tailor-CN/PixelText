package vip.mystery0.pixel.text.ui.screen

import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionConfig
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionType

internal fun NotificationQuickActionType.settingTitle(): String {
    return when (this) {
        NotificationQuickActionType.MARK_READ -> "已阅按钮"
        NotificationQuickActionType.COPY_CODE -> "验证码复制按钮"
        NotificationQuickActionType.REPLY -> "回复按钮"
    }
}

internal fun List<NotificationQuickActionConfig>.settingsSummary(): String {
    return joinToString(separator = " · ") { it.labelTemplate }
}
