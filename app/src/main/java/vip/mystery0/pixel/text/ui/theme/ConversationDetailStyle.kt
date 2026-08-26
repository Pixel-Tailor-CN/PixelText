package vip.mystery0.pixel.text.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import vip.mystery0.pixel.text.domain.theme.ConversationDetailAppearance
import vip.mystery0.pixel.text.domain.theme.MaterialColorRole
import vip.mystery0.pixel.text.domain.theme.ThemeColorReference
import vip.mystery0.pixel.text.domain.theme.ThemeColorType
import vip.mystery0.pixel.text.domain.theme.ThemeConfiguration
import vip.mystery0.pixel.text.domain.theme.ThemeImageReference
import vip.mystery0.pixel.text.domain.theme.ThemeMode
import vip.mystery0.pixel.text.domain.theme.appearance
import vip.mystery0.pixel.text.domain.theme.backgroundAppearance
import kotlin.math.max

private const val MIN_LINK_CONTRAST_RATIO = 3f

@Immutable
data class ResolvedOriginalMessageStyle(
    val receivedTextColor: Color,
    val sentTextColor: Color,
    val receivedBubbleColor: Color,
    val sentBubbleColor: Color,
)

@Immutable
data class ResolvedInputAreaStyle(val backgroundColor: Color)

@Immutable
data class ResolvedConversationBackgroundStyle(
    val image: ThemeImageReference?,
)

@Immutable
data class ResolvedConversationDetailStyle(
    val originalMessage: ResolvedOriginalMessageStyle,
    val inputArea: ResolvedInputAreaStyle,
    val background: ResolvedConversationBackgroundStyle,
    val showSimInfo: Boolean,
    val inputPlaceholder: String,
    val textScale: Float,
    val customizationSuppressed: Boolean,
)

fun MaterialColorRole.resolve(scheme: ColorScheme): Color {
    return when (this) {
        MaterialColorRole.PRIMARY -> scheme.primary
        MaterialColorRole.ON_PRIMARY -> scheme.onPrimary
        MaterialColorRole.PRIMARY_CONTAINER -> scheme.primaryContainer
        MaterialColorRole.ON_PRIMARY_CONTAINER -> scheme.onPrimaryContainer
        MaterialColorRole.SECONDARY -> scheme.secondary
        MaterialColorRole.ON_SECONDARY -> scheme.onSecondary
        MaterialColorRole.SECONDARY_CONTAINER -> scheme.secondaryContainer
        MaterialColorRole.ON_SECONDARY_CONTAINER -> scheme.onSecondaryContainer
        MaterialColorRole.TERTIARY -> scheme.tertiary
        MaterialColorRole.ON_TERTIARY -> scheme.onTertiary
        MaterialColorRole.TERTIARY_CONTAINER -> scheme.tertiaryContainer
        MaterialColorRole.ON_TERTIARY_CONTAINER -> scheme.onTertiaryContainer
        MaterialColorRole.SURFACE -> scheme.surface
        MaterialColorRole.ON_SURFACE -> scheme.onSurface
        MaterialColorRole.SURFACE_VARIANT -> scheme.surfaceVariant
        MaterialColorRole.ON_SURFACE_VARIANT -> scheme.onSurfaceVariant
        MaterialColorRole.ERROR -> scheme.error
        MaterialColorRole.ON_ERROR -> scheme.onError
        MaterialColorRole.ERROR_CONTAINER -> scheme.errorContainer
        MaterialColorRole.ON_ERROR_CONTAINER -> scheme.onErrorContainer
        MaterialColorRole.INVERSE_SURFACE -> scheme.inverseSurface
        MaterialColorRole.INVERSE_ON_SURFACE -> scheme.inverseOnSurface
    }
}

fun ThemeColorReference?.resolveOr(
    scheme: ColorScheme,
    fallback: Color,
): Color {
    val reference = this ?: return fallback
    return when (reference.type) {
        ThemeColorType.MATERIAL_ROLE -> {
            val role = MaterialColorRole.fromStorageValue(reference.value) ?: return fallback
            role.resolve(scheme)
        }
        ThemeColorType.CUSTOM_ARGB -> parseCustomArgb(reference.value) ?: fallback
    }
}

fun resolveConversationDetailStyle(
    configuration: ThemeConfiguration,
    mode: ThemeMode,
    colorScheme: ColorScheme,
    highTextContrastEnabled: Boolean,
): ResolvedConversationDetailStyle {
    val module = configuration.conversationDetail
    if (highTextContrastEnabled) {
        val highContrastText = colorScheme.onSurface
        val highContrastSurface = colorScheme.surface
        return ResolvedConversationDetailStyle(
            originalMessage = ResolvedOriginalMessageStyle(
                receivedTextColor = highContrastText,
                sentTextColor = highContrastText,
                receivedBubbleColor = highContrastSurface,
                sentBubbleColor = highContrastSurface,
            ),
            inputArea = ResolvedInputAreaStyle(backgroundColor = highContrastSurface),
            background = ResolvedConversationBackgroundStyle(image = null),
            showSimInfo = module.showSimInfo,
            inputPlaceholder = module.inputPlaceholder,
            textScale = module.textScale,
            customizationSuppressed = true,
        )
    }

    val appearance: ConversationDetailAppearance = module.appearance(mode)
    val defaultText = colorScheme.onSurface
    val defaultBubble = colorScheme.surfaceVariant
    val defaultInput = colorScheme.surfaceVariant.copy(alpha = 0.5f)

    return ResolvedConversationDetailStyle(
        originalMessage = ResolvedOriginalMessageStyle(
            receivedTextColor = appearance.receivedTextColor.resolveOr(colorScheme, defaultText),
            sentTextColor = appearance.sentTextColor.resolveOr(colorScheme, defaultText),
            receivedBubbleColor = appearance.receivedBubbleColor.resolveOr(
                colorScheme,
                defaultBubble,
            ),
            sentBubbleColor = appearance.sentBubbleColor.resolveOr(colorScheme, defaultBubble),
        ),
        inputArea = ResolvedInputAreaStyle(
            backgroundColor = appearance.inputBackgroundColor.resolveOr(
                colorScheme,
                defaultInput,
            ),
        ),
        background = ResolvedConversationBackgroundStyle(
            image = module.backgroundAppearance(mode).backgroundImage,
        ),
        showSimInfo = module.showSimInfo,
        inputPlaceholder = module.inputPlaceholder,
        textScale = module.textScale,
        customizationSuppressed = false,
    )
}

fun contrastRatio(foreground: Color, background: Color): Float {
    val foregroundLuminance = foreground.compositeOver(background).luminance()
    val backgroundLuminance = background.luminance()
    val lighter = max(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return ((lighter + 0.05f) / (darker + 0.05f))
}

fun readableLinkColor(
    preferred: Color,
    textColor: Color,
    backgroundColor: Color,
): Color {
    return if (contrastRatio(preferred, backgroundColor) < MIN_LINK_CONTRAST_RATIO) {
        textColor
    } else {
        preferred
    }
}

private fun parseCustomArgb(value: String): Color? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("#") || trimmed.length != 9) {
        return null
    }
    val hex = trimmed.substring(1)
    if (hex.any { !it.isHexDigit() }) {
        return null
    }
    val argb = hex.toLongOrNull(16) ?: return null
    return Color(argb.toInt())
}

private fun Char.isHexDigit(): Boolean {
    return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

private fun Color.compositeOver(background: Color): Color {
    val alpha = this.alpha
    if (alpha >= 1f) {
        return this
    }
    if (alpha <= 0f) {
        return background
    }
    val inverseAlpha = 1f - alpha
    val outAlpha = alpha + background.alpha * inverseAlpha
    if (outAlpha <= 0f) {
        return Color.Transparent
    }
    val outRed = (red * alpha + background.red * background.alpha * inverseAlpha) / outAlpha
    val outGreen = (green * alpha + background.green * background.alpha * inverseAlpha) / outAlpha
    val outBlue = (blue * alpha + background.blue * background.alpha * inverseAlpha) / outAlpha
    return Color(red = outRed, green = outGreen, blue = outBlue, alpha = outAlpha)
}
