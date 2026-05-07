package vip.mystery0.pixel.text.ui.message.cards

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
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
    val linkColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
        animationSpec = tween(durationMillis = 200),
        label = "linkColor"
    )

    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf("") }

    // URL 正则表达式（支持带 schema 和不带 schema 的链接，避免误匹配纯数字）
    val urlPattern = remember {
        // 匹配三种情况：
        // 1. 带 http:// 或 https:// 的链接
        // 2. 以 www. 开头的链接
        // 3. 域名格式（至少包含字母，顶级域名必须是字母）
        Regex("(?:https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)|(?:www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)|(?<![\\w.])(?=.*[a-zA-Z])(?:[a-zA-Z0-9][a-zA-Z0-9-]*\\.)+[a-zA-Z]{2,}(?:[/?#][\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?(?![\\w.])")
    }

    // 构建带链接标注的文本
    val annotatedString = remember(content, textColor, linkColor) {
        buildAnnotatedString {
            var lastIndex = 0
            urlPattern.findAll(content).forEach { matchResult ->
                val url = matchResult.value
                val startIndex = matchResult.range.first
                val endIndex = matchResult.range.last + 1

                // 添加 URL 之前的普通文本
                if (startIndex > lastIndex) {
                    append(content.substring(lastIndex, startIndex))
                }

                // 添加 URL 文本，带下划线和颜色
                pushStringAnnotation(tag = "URL", annotation = url)
                pushStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                )
                append(url)
                pop()
                pop()

                lastIndex = endIndex
            }

            // 添加剩余的普通文本
            if (lastIndex < content.length) {
                append(content.substring(lastIndex))
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = backgroundColor,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ClickableText(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                onClick = { offset ->
                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            pendingUrl = annotation.item
                            showDialog = true
                        }
                }
            )
        }
    }

    // 确认对话框
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("打开链接") },
            text = { Text("确定要在浏览器中打开以下链接吗？\n\n$pendingUrl") },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    try {
                        // 如果 URL 没有 schema，自动添加 https://
                        val finalUrl =
                            if (!pendingUrl.startsWith("http://") && !pendingUrl.startsWith("https://")) {
                                "https://$pendingUrl"
                            } else {
                                pendingUrl
                            }
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // 处理打开失败的情况
                    }
                }) {
                    Text("打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
