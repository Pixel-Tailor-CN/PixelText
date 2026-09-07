package vip.mystery0.pixel.text.ui.message.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.ui.message.mms.MmsLocalImage

@Composable
fun MmsImageCard(imageUris: List<String>, isSelected: Boolean = false) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 200),
        label = "mmsImageBg"
    )

    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = backgroundColor,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Column {
            imageUris.forEachIndexed { index, uriString ->
                if (index > 0) Spacer(modifier = Modifier.height(2.dp))
                MmsImage(uri = uriString)
            }
        }
    }
}

@Composable
private fun MmsImage(uri: String) {
    MmsLocalImage(
        uri = uri, cacheKey = uri, cacheEnabled = false,
        description = "彩信图片",
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
}
