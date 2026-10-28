package vip.mystery0.pixel.text.domain.settings

enum class AppDisplayName(val storageValue: String, val label: String) {
    CHISU_FULL("chisu_full", "原点尺素"),
    SMS_FULL("sms_full", "原点短信"),
    JIANXUN_FULL("jianxun_full", "原点简讯"),
    PIXEL_TEXT("pixel_text", "Pixel Text"),
    CHISU("chisu", "尺素"),
    SMS("sms", "短信"),
}

enum class AppIconStyle(val storageValue: String, val label: String) {
    VIOLET("violet", "叠语"),
    BLUE("blue", "原点"),
    GREEN("green", "小零"),
    // 保留旧标识以关闭升级前启用的启动器别名，不再提供该选项。
    APRICOT("apricot", "叠语"),
    CLASSIC("classic", "经典");

    companion object {
        val selectableEntries: List<AppIconStyle> = entries.filter { it != APRICOT }
    }
}

data class AppIdentity(
    val displayName: AppDisplayName = AppDisplayName.CHISU_FULL,
    val iconStyle: AppIconStyle = AppIconStyle.VIOLET,
)
