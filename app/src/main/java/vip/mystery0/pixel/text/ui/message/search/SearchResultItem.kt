package vip.mystery0.pixel.text.ui.message.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.ui.message.formatTimeShort

@Composable
fun SearchResultItem(
    message: MessageModel,
    query: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Color(0xFF000000.toInt() or (message.sender.hashCode() and 0x00FFFFFF)).copy(
                        alpha = 1f
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTimeShort(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))

            val highlightColor = MaterialTheme.colorScheme.primary
            val snippet = remember(message.content, query, highlightColor) {
                getContextSnippet(message.content, query, highlightColor)
            }

            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun getContextSnippet(
    content: String,
    query: String,
    highlightColor: Color,
    contextLength: Int = 30
): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(content)

    val lowerContent = content.lowercase()
    val lowerQuery = query.lowercase()
    val firstIndex = lowerContent.indexOf(lowerQuery)
    if (firstIndex == -1) return AnnotatedString(content)

    // 确定显示的起始和结束范围
    val start = (firstIndex - contextLength).coerceAtLeast(0)
    val end = (firstIndex + query.length + contextLength).coerceAtMost(content.length)

    val prefix = if (start > 0) "..." else ""
    val suffix = if (end < content.length) "..." else ""

    val displayPart = content.substring(start, end)

    return buildAnnotatedString {
        append(prefix)

        var currentPos = 0
        val lowerDisplayPart = displayPart.lowercase()

        while (true) {
            val nextIndex = lowerDisplayPart.indexOf(lowerQuery, currentPos)
            if (nextIndex == -1) {
                append(displayPart.substring(currentPos))
                break
            }

            append(displayPart.substring(currentPos, nextIndex))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor)) {
                append(displayPart.substring(nextIndex, nextIndex + query.length))
            }
            currentPos = nextIndex + query.length
        }

        append(suffix)
    }
}
