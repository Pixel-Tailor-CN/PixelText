package vip.mystery0.pixel.text.ui.message.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.mystery0.pixel.text.domain.model.ParsedResult

@Composable
fun VerificationCodeCard(
    content: String,
    result: ParsedResult.VerificationCode,
    isSelected: Boolean = false,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val baseThemeColor = MaterialTheme.colorScheme.primary
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.inverseSurface
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 200),
        label = "containerColor",
    )
    val onContainerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.inverseOnSurface
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 200),
        label = "onContainerColor",
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) containerColor else baseThemeColor.copy(alpha = 0.15f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(modifier = Modifier.padding(16.dp)) {
                CardHeader(
                    icon = Icons.Rounded.Password,
                    iconTint = if (isSelected) onContainerColor else baseThemeColor,
                    iconBg = if (isSelected) {
                        onContainerColor.copy(alpha = 0.1f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    title = result.signature?.takeIf { it.isNotBlank() } ?: "验证码",
                    dividerColor = onContainerColor.copy(alpha = 0.1f),
                )
            }

            Surface(
                color = containerColor,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = result.code,
                            style = MaterialTheme.typography.displaySmallEmphasized.copy(
                                letterSpacing = 1.sp,
                            ),
                            color = onContainerColor,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) {
                                onContainerColor.copy(alpha = 0.1f)
                            } else {
                                baseThemeColor.copy(alpha = 0.12f)
                            },
                        ) {
                            Text(
                                text = "验证码",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) onContainerColor else baseThemeColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(result.code))
                        },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = onContainerColor.copy(alpha = 0.1f),
                            contentColor = onContainerColor,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "复制验证码",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
