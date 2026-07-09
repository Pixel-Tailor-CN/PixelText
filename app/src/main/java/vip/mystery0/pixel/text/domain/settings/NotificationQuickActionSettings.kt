package vip.mystery0.pixel.text.domain.settings

const val NOTIFICATION_CODE_PLACEHOLDER = "{code}"
private const val NOTIFICATION_ACTION_LABEL_MAX_UNITS = 8

enum class NotificationQuickActionType(val storageValue: String) {
    MARK_READ("mark_read"),
    COPY_CODE("copy_code"),
    REPLY("reply");

    companion object {
        fun fromStorageValue(value: String?): NotificationQuickActionType {
            return entries.firstOrNull { it.storageValue == value } ?: MARK_READ
        }
    }
}

data class NotificationQuickActionConfig(
    val type: NotificationQuickActionType,
    val labelTemplate: String,
    val order: Int,
)

fun defaultNotificationQuickActionConfigs(): List<NotificationQuickActionConfig> {
    return NotificationQuickActionType.entries.mapIndexed { index, type ->
        NotificationQuickActionConfig(
            type = type,
            labelTemplate = type.defaultLabelTemplate(),
            order = index
        )
    }
}

fun List<NotificationQuickActionConfig>.normalizeNotificationQuickActionConfigs(): List<NotificationQuickActionConfig> {
    val defaults = defaultNotificationQuickActionConfigs().associateBy { it.type }
    val merged = NotificationQuickActionType.entries.mapIndexed { defaultIndex, type ->
        val fallback = defaults.getValue(type)
        val current = firstOrNull { it.type == type }
        IndexedNotificationQuickActionConfig(
            defaultIndex = defaultIndex,
            order = current?.order ?: fallback.order,
            config = NotificationQuickActionConfig(
                type = type,
                labelTemplate = current?.labelTemplate
                    ?.trim()
                    ?.ifBlank { fallback.labelTemplate }
                    ?: fallback.labelTemplate,
                order = current?.order ?: fallback.order
            )
        )
    }
    return merged
        .sortedWith(
            compareBy<IndexedNotificationQuickActionConfig> { it.order }
                .thenBy { it.defaultIndex }
        )
        .mapIndexed { index, item ->
            item.config.copy(order = index)
        }
}

fun NotificationQuickActionConfig.renderLabel(code: String): String {
    return if (type == NotificationQuickActionType.COPY_CODE) {
        labelTemplate.replace(NOTIFICATION_CODE_PLACEHOLDER, code)
    } else {
        labelTemplate
    }
}

fun NotificationQuickActionConfig.validationError(): String? {
    val trimmedLabel = labelTemplate.trim()
    if (trimmedLabel.isBlank()) {
        return "文案不能为空"
    }
    return when (type) {
        NotificationQuickActionType.COPY_CODE -> validateCopyCodeLabel(trimmedLabel)
        NotificationQuickActionType.MARK_READ,
        NotificationQuickActionType.REPLY -> validateNormalLabel(trimmedLabel)
    }
}

fun NotificationQuickActionType.defaultLabelTemplate(): String {
    return when (this) {
        NotificationQuickActionType.MARK_READ -> "已阅"
        NotificationQuickActionType.COPY_CODE -> "复制 {code}"
        NotificationQuickActionType.REPLY -> "回复"
    }
}

fun NotificationQuickActionType.preferenceLabelKey(): String {
    return when (this) {
        NotificationQuickActionType.MARK_READ ->
            AppSettingsKeys.KEY_NOTIFICATION_ACTION_MARK_READ_LABEL

        NotificationQuickActionType.COPY_CODE ->
            AppSettingsKeys.KEY_NOTIFICATION_ACTION_COPY_CODE_LABEL

        NotificationQuickActionType.REPLY ->
            AppSettingsKeys.KEY_NOTIFICATION_ACTION_REPLY_LABEL
    }
}

fun NotificationQuickActionType.preferenceOrderKey(): String {
    return when (this) {
        NotificationQuickActionType.MARK_READ ->
            AppSettingsKeys.KEY_NOTIFICATION_ACTION_MARK_READ_ORDER

        NotificationQuickActionType.COPY_CODE ->
            AppSettingsKeys.KEY_NOTIFICATION_ACTION_COPY_CODE_ORDER

        NotificationQuickActionType.REPLY ->
            AppSettingsKeys.KEY_NOTIFICATION_ACTION_REPLY_ORDER
    }
}

fun NotificationQuickActionType.defaultOrder(): Int {
    return when (this) {
        NotificationQuickActionType.MARK_READ -> 0
        NotificationQuickActionType.COPY_CODE -> 1
        NotificationQuickActionType.REPLY -> 2
    }
}

private fun validateNormalLabel(label: String): String? {
    if (label.contains(NOTIFICATION_CODE_PLACEHOLDER)) {
        return "该按钮不支持 {code} 占位符"
    }
    return if (label.displayWidthUnits() > NOTIFICATION_ACTION_LABEL_MAX_UNITS) {
        "文案最多 4 个中文或 8 个英文"
    } else {
        null
    }
}

private fun validateCopyCodeLabel(label: String): String? {
    val placeholderCount = label.countPlaceholderOccurrences()
    if (placeholderCount > 1) {
        return "{code} 最多出现一次"
    }
    val fixedLabel = label.replace(NOTIFICATION_CODE_PLACEHOLDER, "")
    return if (fixedLabel.displayWidthUnits() > NOTIFICATION_ACTION_LABEL_MAX_UNITS) {
        "固定文案最多 4 个中文或 8 个英文"
    } else {
        null
    }
}

private fun String.countPlaceholderOccurrences(): Int {
    if (isEmpty()) return 0
    var count = 0
    var searchStart = 0
    while (true) {
        val index = indexOf(NOTIFICATION_CODE_PLACEHOLDER, startIndex = searchStart)
        if (index < 0) return count
        count += 1
        searchStart = index + NOTIFICATION_CODE_PLACEHOLDER.length
    }
}

private fun String.displayWidthUnits(): Int {
    var index = 0
    var width = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        width += if (codePoint <= 0x7F) 1 else 2
        index += Character.charCount(codePoint)
    }
    return width
}

private data class IndexedNotificationQuickActionConfig(
    val defaultIndex: Int,
    val order: Int,
    val config: NotificationQuickActionConfig,
)
