package vip.mystery0.pixel.text.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R

/** 沿用会话页插画的统一空状态；操作入口由所属页面提供，避免重复按钮。 */
@Composable
fun AppEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes imageRes: Int = R.drawable.illustration_conversation_empty,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val compact = constraints.hasBoundedHeight && maxHeight < 280.dp
        val imageWidth = if (compact) (maxHeight * 0.65f).coerceAtMost(160.dp) else 240.dp
        val scrollModifier = if (constraints.hasBoundedHeight) {
            Modifier.verticalScroll(rememberScrollState())
        } else Modifier
        Column(
            modifier = Modifier.widthIn(max = 360.dp).then(scrollModifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 20.dp),
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                modifier = Modifier.widthIn(max = imageWidth).fillMaxWidth().aspectRatio(4f / 3f),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
