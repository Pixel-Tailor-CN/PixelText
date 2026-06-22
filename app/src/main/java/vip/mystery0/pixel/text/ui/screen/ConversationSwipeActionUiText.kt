package vip.mystery0.pixel.text.ui.screen

import vip.mystery0.pixel.text.domain.settings.ConversationSwipeAction

internal fun ConversationSwipeAction.settingTitle(): String {
    return when (this) {
        ConversationSwipeAction.ARCHIVE -> "归档"
        ConversationSwipeAction.DELETE -> "删除"
        ConversationSwipeAction.TOGGLE_READ -> "标记为已读或未读"
        ConversationSwipeAction.NONE -> "无"
    }
}

internal fun ConversationSwipeAction.settingSummary(): String {
    return when (this) {
        ConversationSwipeAction.ARCHIVE -> "从普通会话列表移入已归档会话"
        ConversationSwipeAction.DELETE -> "移除会话中的短信和彩信，可通过底部提示恢复"
        ConversationSwipeAction.TOGGLE_READ -> "未读会话标记为已读，已读会话标记为未读"
        ConversationSwipeAction.NONE -> "禁用这个方向的滑动手势"
    }
}
