package vip.mystery0.pixel.text.domain.theme

import com.squareup.moshi.JsonClass

const val CURRENT_THEME_SCHEMA_VERSION = 1
const val DEFAULT_CONVERSATION_INPUT_PLACEHOLDER = "请输入"
const val DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE = 1f
const val MIN_CONVERSATION_DETAIL_TEXT_SCALE = 0.85f
const val MAX_CONVERSATION_DETAIL_TEXT_SCALE = 1.8f
const val MAX_CONVERSATION_INPUT_PLACEHOLDER_LENGTH = 40

@JsonClass(generateAdapter = true)
data class ThemeConfiguration(
    val schemaVersion: Int = CURRENT_THEME_SCHEMA_VERSION,
    val conversationDetail: ConversationDetailThemeModule =
        ConversationDetailThemeModule(),
)

@JsonClass(generateAdapter = true)
data class ConversationDetailThemeModule(
    val light: ConversationDetailAppearance = ConversationDetailAppearance(),
    val dark: ConversationDetailAppearance = ConversationDetailAppearance(),
    val showSimInfo: Boolean = true,
    val inputPlaceholder: String = DEFAULT_CONVERSATION_INPUT_PLACEHOLDER,
    val textScale: Float = DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE,
)

@JsonClass(generateAdapter = true)
data class ConversationDetailAppearance(
    val receivedTextColor: ThemeColorReference? = null,
    val sentTextColor: ThemeColorReference? = null,
    val receivedBubbleColor: ThemeColorReference? = null,
    val sentBubbleColor: ThemeColorReference? = null,
    val inputBackgroundColor: ThemeColorReference? = null,
    val backgroundImage: ThemeImageReference? = null,
)

@JsonClass(generateAdapter = true)
data class ThemeColorReference(
    val type: ThemeColorType,
    val value: String,
)

@JsonClass(generateAdapter = true)
data class ThemeImageReference(val assetId: String)

enum class ThemeMode { LIGHT, DARK }

enum class ThemeColorType { MATERIAL_ROLE, CUSTOM_ARGB }

enum class MaterialColorRole(val storageValue: String) {
    PRIMARY("primary"),
    ON_PRIMARY("on_primary"),
    PRIMARY_CONTAINER("primary_container"),
    ON_PRIMARY_CONTAINER("on_primary_container"),
    SECONDARY("secondary"),
    ON_SECONDARY("on_secondary"),
    SECONDARY_CONTAINER("secondary_container"),
    ON_SECONDARY_CONTAINER("on_secondary_container"),
    TERTIARY("tertiary"),
    ON_TERTIARY("on_tertiary"),
    TERTIARY_CONTAINER("tertiary_container"),
    ON_TERTIARY_CONTAINER("on_tertiary_container"),
    SURFACE("surface"),
    ON_SURFACE("on_surface"),
    SURFACE_VARIANT("surface_variant"),
    ON_SURFACE_VARIANT("on_surface_variant"),
    ERROR("error"),
    ON_ERROR("on_error"),
    ERROR_CONTAINER("error_container"),
    ON_ERROR_CONTAINER("on_error_container"),
    INVERSE_SURFACE("inverse_surface"),
    INVERSE_ON_SURFACE("inverse_on_surface");

    companion object {
        fun fromStorageValue(value: String?): MaterialColorRole? {
            return entries.firstOrNull { it.storageValue == value }
        }
    }
}

fun ThemeConfiguration.normalized(): ThemeConfiguration {
    return copy(
        schemaVersion = CURRENT_THEME_SCHEMA_VERSION,
        conversationDetail = conversationDetail.normalized(),
    )
}

fun ConversationDetailThemeModule.appearance(mode: ThemeMode): ConversationDetailAppearance {
    return when (mode) {
        ThemeMode.LIGHT -> light
        ThemeMode.DARK -> dark
    }
}

fun ConversationDetailThemeModule.withAppearance(
    mode: ThemeMode,
    appearance: ConversationDetailAppearance,
): ConversationDetailThemeModule {
    return when (mode) {
        ThemeMode.LIGHT -> copy(light = appearance)
        ThemeMode.DARK -> copy(dark = appearance)
    }
}

private fun ConversationDetailThemeModule.normalized(): ConversationDetailThemeModule {
    val normalizedPlaceholder = inputPlaceholder
        .trim()
        .take(MAX_CONVERSATION_INPUT_PLACEHOLDER_LENGTH)
        .ifEmpty { DEFAULT_CONVERSATION_INPUT_PLACEHOLDER }
    val normalizedScale = textScale
        .takeIf { it.isFinite() }
        ?.coerceIn(
            MIN_CONVERSATION_DETAIL_TEXT_SCALE,
            MAX_CONVERSATION_DETAIL_TEXT_SCALE,
        )
        ?: DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE
    return copy(
        light = light.normalized(),
        dark = dark.normalized(),
        inputPlaceholder = normalizedPlaceholder,
        textScale = normalizedScale,
    )
}

private fun ConversationDetailAppearance.normalized(): ConversationDetailAppearance {
    return copy(
        receivedTextColor = receivedTextColor.normalizedOrNull(),
        sentTextColor = sentTextColor.normalizedOrNull(),
        receivedBubbleColor = receivedBubbleColor.normalizedOrNull(),
        sentBubbleColor = sentBubbleColor.normalizedOrNull(),
        inputBackgroundColor = inputBackgroundColor.normalizedOrNull(),
        backgroundImage = backgroundImage.normalizedOrNull(),
    )
}

private fun ThemeColorReference?.normalizedOrNull(): ThemeColorReference? {
    val reference = this ?: return null
    return when (reference.type) {
        ThemeColorType.MATERIAL_ROLE -> {
            val role = MaterialColorRole.fromStorageValue(reference.value.trim()) ?: return null
            ThemeColorReference(
                type = ThemeColorType.MATERIAL_ROLE,
                value = role.storageValue,
            )
        }
        ThemeColorType.CUSTOM_ARGB -> {
            val argb = normalizeCustomArgb(reference.value) ?: return null
            ThemeColorReference(
                type = ThemeColorType.CUSTOM_ARGB,
                value = argb,
            )
        }
    }
}

private fun ThemeImageReference?.normalizedOrNull(): ThemeImageReference? {
    val reference = this ?: return null
    val assetId = reference.assetId.trim()
    if (assetId.isEmpty()) {
        return null
    }
    return ThemeImageReference(assetId = assetId)
}

private fun normalizeCustomArgb(value: String): String? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("#") || trimmed.length != 9) {
        return null
    }
    val hex = trimmed.substring(1)
    if (hex.any { !it.isHexDigit() }) {
        return null
    }
    return "#${hex.uppercase()}"
}

private fun Char.isHexDigit(): Boolean {
    return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
