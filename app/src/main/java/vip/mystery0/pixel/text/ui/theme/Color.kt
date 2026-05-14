package vip.mystery0.pixel.text.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

// Google Messages 风格联系人头像背景色
val AvatarColors = listOf(
    Color(0xFFFE63B7), // Pink
    Color(0xFFFA903D), // Orange
    Color(0xFF5CB973), // Green
    Color(0xFFAF5CF6), // Purple
    Color(0xFFED675C), // Red
    Color(0xFF4ECDE6), // Cyan
)

fun getAvatarColor(identifier: String?): Color {
    if (identifier.isNullOrEmpty()) return AvatarColors[0]
    val index = abs(identifier.hashCode()) % AvatarColors.size
    return AvatarColors[index]
}