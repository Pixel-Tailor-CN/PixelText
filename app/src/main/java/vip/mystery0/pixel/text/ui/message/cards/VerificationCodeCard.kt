package vip.mystery0.pixel.text.ui.message.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
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
fun VerificationCodeCard(content: String, result: ParsedResult.VerificationCode, isSelected: Boolean = false) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(durationMillis = 200),
        label = "containerColor"
    )
    val onContainerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onPrimaryContainer,
        animationSpec = tween(durationMillis = 200),
        label = "onContainerColor"
    )

    ElevatedCard(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = containerColor
        ),
        modifier = Modifier.widthIn(min = 260.dp, max = 360.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = result.code,
                    style = MaterialTheme.typography.displaySmallEmphasized.copy(
                        letterSpacing = 1.sp
                    ),
                    color = onContainerColor,
                    maxLines = 1
                )
                Text(
                    text = "验证码",
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainerColor.copy(alpha = 0.62f)
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(result.code))
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = onContainerColor.copy(alpha = 0.1f),
                    contentColor = onContainerColor
                ),
                modifier = Modifier
                    .height(56.dp)
                    .widthIn(min = 104.dp)
            ) {
                Text(
                    text = "复制",
                    style = MaterialTheme.typography.titleMediumEmphasized
                )
            }
        }
    }
}
