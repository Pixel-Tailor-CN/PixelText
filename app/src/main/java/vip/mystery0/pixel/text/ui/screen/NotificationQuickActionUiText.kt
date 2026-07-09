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

internal fun NotificationQuickActionType.settingSummary(): String {
    return when (this) {
        NotificationQuickActionType.MARK_READ -> "收到新短信后快速标记当前会话为已读"
        NotificationQuickActionType.COPY_CODE -> "仅在验证码通知中显示，可使用 {code} 占位符"
        NotificationQuickActionType.REPLY -> "直接从通知栏输入并发送回复内容"
    }
}

internal fun List<NotificationQuickActionConfig>.settingsSummary(): String {
    return joinToString(separator = " · ") { it.labelTemplate }
}
