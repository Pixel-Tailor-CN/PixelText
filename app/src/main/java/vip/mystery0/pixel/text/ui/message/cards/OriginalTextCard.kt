package vip.mystery0.pixel.text.ui.message.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OriginalTextCard(content: String, isSelected: Boolean = false) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 200),
        label = "bubbleBackground"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 200),
        label = "bubbleText"
    )

    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = backgroundColor,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }
    }
}
