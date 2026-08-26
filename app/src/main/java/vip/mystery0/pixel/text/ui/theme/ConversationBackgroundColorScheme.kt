@file:Suppress("RestrictedApi")

package vip.mystery0.pixel.text.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.Score
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.theme.ThemeMode

private const val TAG = "ConversationBgColors"
private const val MAX_QUANTIZED_IMAGE_DIMENSION = 128
private const val MAX_QUANTIZED_COLORS = 128
private val seedColorCache = LruCache<String, Int>(8)
private val materialColors = MaterialDynamicColors()

@Immutable
data class ConversationBackgroundColorSchemeResult(
    val colorScheme: ColorScheme,
    val usesBackgroundImageColors: Boolean,
    val isLoading: Boolean,
)

private sealed interface SeedColorState {
    data object Disabled : SeedColorState
    data object Loading : SeedColorState
    data object Unavailable : SeedColorState
    data class Ready(val color: Int) : SeedColorState
}

@Composable
fun rememberConversationBackgroundColorScheme(
    file: File?,
    mode: ThemeMode,
    enabled: Boolean,
    fallback: ColorScheme,
): ConversationBackgroundColorSchemeResult {
    val cacheKey = file?.takeIf(File::isFile)?.let(::backgroundCacheKey)
    val cachedSeed = cacheKey?.let(seedColorCache::get)
    val seedState by produceState<SeedColorState>(
        initialValue = when {
            !enabled || cacheKey == null -> SeedColorState.Disabled
            cachedSeed != null -> SeedColorState.Ready(cachedSeed)
            else -> SeedColorState.Loading
        },
        enabled,
        cacheKey,
    ) {
        when {
            !enabled || cacheKey == null -> value = SeedColorState.Disabled
            cachedSeed != null -> value = SeedColorState.Ready(cachedSeed)
            else -> {
                value = SeedColorState.Loading
                value = withContext(Dispatchers.Default) {
                    extractSeedColor(file)
                }?.let { seed ->
                    seedColorCache.put(cacheKey, seed)
                    SeedColorState.Ready(seed)
                } ?: SeedColorState.Unavailable
            }
        }
    }
    val seedColor = (seedState as? SeedColorState.Ready)?.color
    val generated = remember(seedColor, mode, fallback) {
        seedColor?.let { seed -> generateMaterialColorScheme(seed, mode, fallback) }
    }
    return ConversationBackgroundColorSchemeResult(
        colorScheme = generated ?: fallback,
        usesBackgroundImageColors = enabled && generated != null,
        isLoading = seedState == SeedColorState.Loading,
    )
}

private fun backgroundCacheKey(file: File): String {
    return "${file.absolutePath}:${file.length()}:${file.lastModified()}"
}

private fun extractSeedColor(file: File): Int? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_QUANTIZED_IMAGE_DIMENSION * 2 ||
            bounds.outHeight / sampleSize > MAX_QUANTIZED_IMAGE_DIMENSION * 2
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val bitmap = scaleForQuantization(decoded)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (bitmap !== decoded) {
            bitmap.recycle()
        }
        decoded.recycle()
        val opaquePixels = pixels.filter { color -> color ushr 24 >= 0x80 }.toIntArray()
        if (opaquePixels.isEmpty()) {
            return null
        }
        Score.score(
            QuantizerCelebi.quantize(opaquePixels, MAX_QUANTIZED_COLORS),
        ).firstOrNull()
    } catch (error: Exception) {
        Log.w(TAG, "background color extraction failed path=${file.absolutePath}", error)
        null
    }
}

private fun scaleForQuantization(bitmap: Bitmap): Bitmap {
    val largestDimension = maxOf(bitmap.width, bitmap.height)
    if (largestDimension <= MAX_QUANTIZED_IMAGE_DIMENSION) {
        return bitmap
    }
    val scale = MAX_QUANTIZED_IMAGE_DIMENSION.toFloat() / largestDimension
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}

private fun generateMaterialColorScheme(
    seedColor: Int,
    mode: ThemeMode,
    fallback: ColorScheme,
): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seedColor), mode == ThemeMode.DARK, 0.0)
    fun color(role: DynamicColor): Color = Color(role.getArgb(scheme))
    return fallback.copy(
        primary = color(materialColors.primary()),
        onPrimary = color(materialColors.onPrimary()),
        primaryContainer = color(materialColors.primaryContainer()),
        onPrimaryContainer = color(materialColors.onPrimaryContainer()),
        inversePrimary = color(materialColors.inversePrimary()),
        secondary = color(materialColors.secondary()),
        onSecondary = color(materialColors.onSecondary()),
        secondaryContainer = color(materialColors.secondaryContainer()),
        onSecondaryContainer = color(materialColors.onSecondaryContainer()),
        tertiary = color(materialColors.tertiary()),
        onTertiary = color(materialColors.onTertiary()),
        tertiaryContainer = color(materialColors.tertiaryContainer()),
        onTertiaryContainer = color(materialColors.onTertiaryContainer()),
        background = color(materialColors.background()),
        onBackground = color(materialColors.onBackground()),
        surface = color(materialColors.surface()),
        onSurface = color(materialColors.onSurface()),
        surfaceVariant = color(materialColors.surfaceVariant()),
        onSurfaceVariant = color(materialColors.onSurfaceVariant()),
        surfaceTint = color(materialColors.surfaceTint()),
        inverseSurface = color(materialColors.inverseSurface()),
        inverseOnSurface = color(materialColors.inverseOnSurface()),
        error = color(materialColors.error()),
        onError = color(materialColors.onError()),
        errorContainer = color(materialColors.errorContainer()),
        onErrorContainer = color(materialColors.onErrorContainer()),
        outline = color(materialColors.outline()),
        outlineVariant = color(materialColors.outlineVariant()),
        scrim = color(materialColors.scrim()),
        surfaceBright = color(materialColors.surfaceBright()),
        surfaceDim = color(materialColors.surfaceDim()),
        surfaceContainer = color(materialColors.surfaceContainer()),
        surfaceContainerHigh = color(materialColors.surfaceContainerHigh()),
        surfaceContainerHighest = color(materialColors.surfaceContainerHighest()),
        surfaceContainerLow = color(materialColors.surfaceContainerLow()),
        surfaceContainerLowest = color(materialColors.surfaceContainerLowest()),
        primaryFixed = color(materialColors.primaryFixed()),
        primaryFixedDim = color(materialColors.primaryFixedDim()),
        onPrimaryFixed = color(materialColors.onPrimaryFixed()),
        onPrimaryFixedVariant = color(materialColors.onPrimaryFixedVariant()),
        secondaryFixed = color(materialColors.secondaryFixed()),
        secondaryFixedDim = color(materialColors.secondaryFixedDim()),
        onSecondaryFixed = color(materialColors.onSecondaryFixed()),
        onSecondaryFixedVariant = color(materialColors.onSecondaryFixedVariant()),
        tertiaryFixed = color(materialColors.tertiaryFixed()),
        tertiaryFixedDim = color(materialColors.tertiaryFixedDim()),
        onTertiaryFixed = color(materialColors.onTertiaryFixed()),
        onTertiaryFixedVariant = color(materialColors.onTertiaryFixedVariant()),
    )
}
