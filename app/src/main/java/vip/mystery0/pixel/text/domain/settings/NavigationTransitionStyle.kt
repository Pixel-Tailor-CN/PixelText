package vip.mystery0.pixel.text.domain.settings

enum class NavigationTransitionStyle(val storageValue: String) {
    DEFAULT("default"),
    SLIDE("slide"),
    FADE("fade"),
    NONE("none");

    companion object {
        fun fromStorageValue(value: String?): NavigationTransitionStyle {
            return entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        }
    }
}
