package vip.mystery0.pixel.text.ui.message.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.mystery0.pixel.text.domain.model.ParsedResult

@Composable
fun VerificationCodeCard(content: String, result: ParsedResult.VerificationCode, isSelected: Boolean = false) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val containerColor = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.primaryContainer
    val onContainerColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onPrimaryContainer

    ElevatedCard(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = containerColor
        ),
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = onContainerColor.copy(alpha = 0.1f),
                onClick = {
                    clipboardManager.setText(AnnotatedString(result.code))
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val titleText = if (result.signature != null) {
                            "${result.signature} · 验证码"
                        } else {
                            "验证码"
                        }
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainerColor.copy(alpha = 0.7f)
                        )
                        Text(
                            text = result.code,
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            color = onContainerColor,
                            letterSpacing = 4.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = if (isSelected) onContainerColor else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
