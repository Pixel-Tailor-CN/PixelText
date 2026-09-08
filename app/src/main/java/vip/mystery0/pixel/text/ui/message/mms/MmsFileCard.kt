package vip.mystery0.pixel.text.ui.message.mms

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPartKey
import vip.mystery0.pixel.text.data.source.mms.suggestedMmsAttachmentName

@Composable
fun MmsFileCard(
    part: MmsPartContent,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    interactionEnabled: Boolean = true,
    onMessageClick: () -> Unit = {},
    onOpenPart: (MmsPartKey) -> Unit = {},
    onFeedback: ((String) -> Unit)? = null,
    htmlPreview: Boolean = false,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.inverseSurface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(200),
        label = "mmsFileContainer",
    )
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.inverseOnSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val cardClickEnabled = interactionEnabled && (selectionMode || part.statusInfo().actionable)
    Surface(
        modifier = modifier
            .widthIn(max = 360.dp)
            .clickable(enabled = cardClickEnabled) {
                if (selectionMode) onMessageClick() else onOpenPart(part.key)
            },
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = suggestedMmsAttachmentName(part),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(part.mimeType, formatByteCount(part.byteCount)).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (htmlPreview) {
                Text(
                    text = part.htmlSummary?.takeIf { it.isNotBlank() } ?: "暂无可读摘要",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    enabled = interactionEnabled && !selectionMode && part.text != null,
                    onClick = { onOpenPart(part.key) },
                ) { Text("查看完整内容") }
            }
            MmsPartStatus(part, Modifier.padding(top = 8.dp))
            MmsAttachmentActions(
                part = part,
                modifier = Modifier.fillMaxWidth(),
                enabled = interactionEnabled && !selectionMode,
                onFeedback = onFeedback,
                openLabel = if (htmlPreview) "外部打开" else "打开",
            )
        }
    }
}

private fun formatByteCount(bytes: Long?): String {
    bytes ?: return "大小未知"
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return String.format(Locale.getDefault(), "%.1f KB", kib)
    val mib = kib / 1024.0
    if (mib < 1024) return String.format(Locale.getDefault(), "%.1f MB", mib)
    return String.format(Locale.getDefault(), "%.1f GB", mib / 1024.0)
}
