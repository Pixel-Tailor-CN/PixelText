package vip.mystery0.pixel.text.domain.settings

fun formatResourceVersionForDisplay(
    resourceVersion: String,
    bundledVersion: String?,
): String {
    val normalizedBundledVersion = bundledVersion
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != AppSettingsKeys.DEFAULT_RESOURCE_VERSION }
    if (
        resourceVersion == AppSettingsKeys.DEFAULT_RESOURCE_VERSION &&
        normalizedBundledVersion != null
    ) {
        return "${AppSettingsKeys.DEFAULT_RESOURCE_VERSION}($normalizedBundledVersion)"
    }
    return resourceVersion
}
