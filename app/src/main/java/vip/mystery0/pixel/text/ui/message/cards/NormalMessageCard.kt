package vip.mystery0.pixel.text.ui.message.cards

import androidx.compose.runtime.Composable

@Composable
fun NormalMessageCard(content: String, isSelected: Boolean = false) {
    OriginalTextCard(content = content, isSelected = isSelected)
}
