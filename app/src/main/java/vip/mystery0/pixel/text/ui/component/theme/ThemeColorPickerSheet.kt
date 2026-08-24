package vip.mystery0.pixel.text.ui.component.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.theme.MaterialColorRole
import vip.mystery0.pixel.text.domain.theme.ThemeColorReference
import vip.mystery0.pixel.text.domain.theme.ThemeColorType
import vip.mystery0.pixel.text.ui.theme.contrastRatio
import vip.mystery0.pixel.text.ui.theme.resolve
import vip.mystery0.pixel.text.ui.theme.resolveOr
import java.util.Locale

private const val MIN_RECOMMENDED_CONTRAST = 4.5f

private enum class ColorPickerSource {
    DEFAULT,
    MATERIAL_ROLE,
    CUSTOM,
}

/**
 * Parses theme HEX input.
 * Accepts `#RRGGBB` (alpha becomes `FF`) and `#AARRGGBB`. Invalid input returns null.
 */
fun parseThemeHexColor(value: String): Color? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("#")) {
        return null
    }
    val hex = trimmed.substring(1)
    val normalized = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    if (normalized.any { !it.isHexDigit() }) {
        return null
    }
    val argb = normalized.toLongOrNull(16) ?: return null
    return Color(argb.toInt())
}

/** Formats a Compose [Color] as standardized `#AARRGGBB`. */
fun Color.toThemeHex(): String {
    val argb = toArgb().toLong() and 0xFFFFFFFFL
    return "#%08X".format(Locale.US, argb)
}

/** Returns HSV components as `[hue 0..360, saturation 0..1, value 0..1]`. */
fun Color.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return hsv
}

fun colorFromHsv(hue: Float, saturation: Float, value: Float): Color {
    val colorInt = android.graphics.Color.HSVToColor(
        floatArrayOf(
            hue.coerceIn(0f, 360f),
            saturation.coerceIn(0f, 1f),
            value.coerceIn(0f, 1f),
        ),
    )
    return Color(colorInt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeColorPickerSheet(
    title: String,
    current: ThemeColorReference?,
    colorScheme: ColorScheme,
    comparisonBackground: Color,
    /**
     * Target-specific official default used for “使用官方默认” preview/contrast and as the
     * starting custom HSV when [current] is null (e.g. surfaceVariant for bubbles).
     */
    officialDefault: Color,
    onDismiss: () -> Unit,
    onConfirm: (ThemeColorReference?) -> Unit,
    /**
     * When true, [comparisonBackground] is treated as foreground text and the selected color is
     * the surface/bubble behind it (used for bubble and input-background pickers).
     */
    selectedActsAsBackground: Boolean = false,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val initialResolved = current.resolveOr(colorScheme, officialDefault)
    val initialHsv = remember(current, colorScheme, officialDefault) { initialResolved.toHsv() }

    var source by remember(current) {
        mutableStateOf(
            when (current?.type) {
                null -> ColorPickerSource.DEFAULT
                ThemeColorType.MATERIAL_ROLE -> ColorPickerSource.MATERIAL_ROLE
                ThemeColorType.CUSTOM_ARGB -> ColorPickerSource.CUSTOM
            },
        )
    }
    var selectedRole by remember(current) {
        mutableStateOf(
            current
                ?.takeIf { it.type == ThemeColorType.MATERIAL_ROLE }
                ?.let { MaterialColorRole.fromStorageValue(it.value) },
        )
    }
    var hue by remember(current, colorScheme, officialDefault) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(current, colorScheme, officialDefault) {
        mutableFloatStateOf(initialHsv[1])
    }
    var value by remember(current, colorScheme, officialDefault) { mutableFloatStateOf(initialHsv[2]) }
    var customAlpha by remember(current, colorScheme, officialDefault) {
        mutableFloatStateOf(initialResolved.alpha.coerceIn(0f, 1f))
    }
    var hexInput by remember(current, colorScheme, officialDefault) {
        mutableStateOf(initialResolved.toThemeHex())
    }
    var hexEdited by remember(current) { mutableStateOf(false) }

    val customColor = colorFromHsv(hue, saturation, value).copy(alpha = customAlpha)
    val selectedColor: Color? = when (source) {
        ColorPickerSource.DEFAULT -> null
        ColorPickerSource.MATERIAL_ROLE -> selectedRole?.resolve(colorScheme)
        ColorPickerSource.CUSTOM -> {
            if (hexEdited) {
                parseThemeHexColor(hexInput)
            } else {
                customColor
            }
        }
    }

    LaunchedEffect(customColor, source, hexEdited) {
        if (source == ColorPickerSource.CUSTOM && !hexEdited) {
            hexInput = customColor.toThemeHex()
        }
    }

    val parsedHex = parseThemeHexColor(hexInput)
    val confirmEnabled = when (source) {
        ColorPickerSource.DEFAULT -> true
        ColorPickerSource.MATERIAL_ROLE -> selectedRole != null
        ColorPickerSource.CUSTOM -> parsedHex != null
    }
    val previewColor = selectedColor ?: officialDefault
    val previewForeground = if (selectedActsAsBackground) comparisonBackground else previewColor
    val previewBackground = if (selectedActsAsBackground) previewColor else comparisonBackground
    val ratio = contrastRatio(previewForeground, previewBackground)
    val lowContrast = ratio < MIN_RECOMMENDED_CONTRAST

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )

            ColorCombinationPreview(
                foreground = previewForeground,
                background = previewBackground,
                contrastRatio = ratio,
                lowContrast = lowContrast,
                usingDefault = source == ColorPickerSource.DEFAULT,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item(key = "default") {
                    SourceRow(
                        selected = source == ColorPickerSource.DEFAULT,
                        title = "使用官方默认",
                        supporting = "跟随 Material You 默认样式",
                        swatch = officialDefault,
                        onClick = {
                            source = ColorPickerSource.DEFAULT
                            selectedRole = null
                            hexEdited = false
                        },
                    )
                }

                item(key = "material_header") {
                    Text(
                        text = "Material 3 颜色角色",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }

                items(
                    items = MaterialColorRole.entries,
                    key = { it.storageValue },
                ) { role ->
                    val roleColor = role.resolve(colorScheme)
                    SourceRow(
                        selected = source == ColorPickerSource.MATERIAL_ROLE &&
                            selectedRole == role,
                        title = role.displayName(),
                        supporting = role.storageValue,
                        swatch = roleColor,
                        onClick = {
                            source = ColorPickerSource.MATERIAL_ROLE
                            selectedRole = role
                            hexEdited = false
                            val hsv = roleColor.toHsv()
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                            customAlpha = roleColor.alpha
                            hexInput = roleColor.toThemeHex()
                        },
                    )
                }

                item(key = "custom_header") {
                    Text(
                        text = "自定义颜色",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }

                item(key = "hsv_picker") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SaturationValuePicker(
                            hue = hue,
                            saturation = saturation,
                            value = value,
                            onChange = { nextSaturation, nextValue ->
                                source = ColorPickerSource.CUSTOM
                                hexEdited = false
                                saturation = nextSaturation
                                value = nextValue
                            },
                        )
                        HueBar(
                            hue = hue,
                            onChange = { nextHue ->
                                source = ColorPickerSource.CUSTOM
                                hexEdited = false
                                hue = nextHue
                            },
                        )
                        OutlinedTextField(
                            value = hexInput,
                            onValueChange = { input ->
                                hexInput = input
                                hexEdited = true
                                source = ColorPickerSource.CUSTOM
                                val parsed = parseThemeHexColor(input)
                                if (parsed != null) {
                                    val hsv = parsed.toHsv()
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    value = hsv[2]
                                    customAlpha = parsed.alpha
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("HEX") },
                            placeholder = { Text("#AARRGGBB") },
                            singleLine = true,
                            isError = source == ColorPickerSource.CUSTOM && parsedHex == null,
                            supportingText = {
                                Text(
                                    if (source == ColorPickerSource.CUSTOM && parsedHex == null) {
                                        "支持 #RRGGBB 或 #AARRGGBB"
                                    } else {
                                        "将保存为 #AARRGGBB"
                                    },
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val reference = when (source) {
                            ColorPickerSource.DEFAULT -> null
                            ColorPickerSource.MATERIAL_ROLE -> {
                                val role = selectedRole ?: return@Button
                                ThemeColorReference(
                                    type = ThemeColorType.MATERIAL_ROLE,
                                    value = role.storageValue,
                                )
                            }
                            ColorPickerSource.CUSTOM -> {
                                val color = parsedHex ?: return@Button
                                ThemeColorReference(
                                    type = ThemeColorType.CUSTOM_ARGB,
                                    value = color.toThemeHex(),
                                )
                            }
                        }
                        onConfirm(reference)
                    },
                    enabled = confirmEnabled,
                ) {
                    Text("确定")
                }
            }
        }
    }
}

@Composable
private fun ColorCombinationPreview(
    foreground: Color,
    background: Color,
    contrastRatio: Float,
    lowContrast: Boolean,
    usingDefault: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(foreground)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (usingDefault) "官方默认预览" else "文字预览",
                color = foreground,
                style = MaterialTheme.typography.titleMedium,
            )
            val ratioLabel = "对比度 ${"%.1f".format(Locale.US, contrastRatio)}:1"
            Text(
                text = ratioLabel,
                color = foreground.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (lowContrast) {
                Text(
                    text = "对比度低于 4.5:1，可能影响可读性",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    selected: Boolean,
    title: String,
    supporting: String,
    swatch: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaturationValuePicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit,
) {
    val hueColor = colorFromHsv(hue, 1f, 1f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    val nextSaturation = (offset.x / size.width).coerceIn(0f, 1f)
                    val nextValue = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    onChange(nextSaturation, nextValue)
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val nextSaturation = (change.position.x / size.width).coerceIn(0f, 1f)
                    val nextValue = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    onChange(nextSaturation, nextValue)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val indicator = Offset(
            x = saturation * size.width,
            y = (1f - value) * size.height,
        )
        drawCircle(
            color = Color.White,
            radius = 10.dp.toPx(),
            center = indicator,
        )
        drawCircle(
            color = colorFromHsv(hue, saturation, value),
            radius = 7.dp.toPx(),
            center = indicator,
        )
    }
}

@Composable
private fun HueBar(
    hue: Float,
    onChange: (Float) -> Unit,
) {
    val colors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red,
        )
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val nextHue = ((offset.x / size.width) * 360f).coerceIn(0f, 360f)
                    onChange(nextHue)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val nextHue = ((change.position.x / size.width) * 360f).coerceIn(0f, 360f)
                    onChange(nextHue)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(colors))
        val x = (hue / 360f) * size.width
        drawCircle(
            color = Color.White,
            radius = 12.dp.toPx(),
            center = Offset(x, size.height / 2f),
        )
        drawCircle(
            color = colorFromHsv(hue, 1f, 1f),
            radius = 8.dp.toPx(),
            center = Offset(x, size.height / 2f),
        )
    }
}

private fun MaterialColorRole.displayName(): String {
    return when (this) {
        MaterialColorRole.PRIMARY -> "主色"
        MaterialColorRole.ON_PRIMARY -> "主色上的内容"
        MaterialColorRole.PRIMARY_CONTAINER -> "主色容器"
        MaterialColorRole.ON_PRIMARY_CONTAINER -> "主色容器上的内容"
        MaterialColorRole.SECONDARY -> "次色"
        MaterialColorRole.ON_SECONDARY -> "次色上的内容"
        MaterialColorRole.SECONDARY_CONTAINER -> "次色容器"
        MaterialColorRole.ON_SECONDARY_CONTAINER -> "次色容器上的内容"
        MaterialColorRole.TERTIARY -> "第三色"
        MaterialColorRole.ON_TERTIARY -> "第三色上的内容"
        MaterialColorRole.TERTIARY_CONTAINER -> "第三色容器"
        MaterialColorRole.ON_TERTIARY_CONTAINER -> "第三色容器上的内容"
        MaterialColorRole.SURFACE -> "表面"
        MaterialColorRole.ON_SURFACE -> "表面上的内容"
        MaterialColorRole.SURFACE_VARIANT -> "表面变体"
        MaterialColorRole.ON_SURFACE_VARIANT -> "表面变体上的内容"
        MaterialColorRole.ERROR -> "错误"
        MaterialColorRole.ON_ERROR -> "错误上的内容"
        MaterialColorRole.ERROR_CONTAINER -> "错误容器"
        MaterialColorRole.ON_ERROR_CONTAINER -> "错误容器上的内容"
        MaterialColorRole.INVERSE_SURFACE -> "反色表面"
        MaterialColorRole.INVERSE_ON_SURFACE -> "反色表面上的内容"
    }
}

private fun Char.isHexDigit(): Boolean {
    return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
