package vip.mystery0.pixel.text.ui.screen

import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.settings.ConversationSwipeAction
import vip.mystery0.pixel.text.util.AppStrings

internal fun ConversationSwipeAction.settingTitle(): String {
    val resId = when (this) {
        ConversationSwipeAction.ARCHIVE -> R.string.settings_swipe_archive_title
        ConversationSwipeAction.DELETE -> R.string.settings_swipe_delete_title
        ConversationSwipeAction.TOGGLE_READ -> R.string.settings_swipe_toggle_read_title
        ConversationSwipeAction.NONE -> R.string.settings_swipe_none_title
    }
    return AppStrings.get(resId)
}

internal fun ConversationSwipeAction.settingSummary(): String {
    val resId = when (this) {
        ConversationSwipeAction.ARCHIVE -> R.string.settings_swipe_archive_summary
        ConversationSwipeAction.DELETE -> R.string.settings_swipe_delete_summary
        ConversationSwipeAction.TOGGLE_READ -> R.string.settings_swipe_toggle_read_summary
        ConversationSwipeAction.NONE -> R.string.settings_swipe_none_summary
    }
    return AppStrings.get(resId)
}
