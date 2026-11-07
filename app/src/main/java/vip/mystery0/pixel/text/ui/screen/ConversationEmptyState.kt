package vip.mystery0.pixel.text.ui.screen

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import vip.mystery0.pixel.text.ui.component.AppEmptyState

@Composable
fun ConversationEmptyState(
    @DrawableRes imageRes: Int,
    text: String
) {
    AppEmptyState(text = text, imageRes = imageRes, modifier = Modifier.fillMaxSize())
}
