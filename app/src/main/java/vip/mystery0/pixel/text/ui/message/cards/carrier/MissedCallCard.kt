package vip.mystery0.pixel.text.ui.message.cards.carrier

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PhoneCallback
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.CardHeader
import vip.mystery0.pixel.text.ui.message.cards.InfoMapList
import vip.mystery0.pixel.text.ui.message.cards.smartCardContainerColor

@Composable
fun MissedCallCard(result: ParsedResult.MissedCall, isSelected: Boolean = false) {
    val context = LocalContext.current
    val baseThemeColor = MaterialTheme.colorScheme.primary
    val themeColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else baseThemeColor
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.inverseSurface
    } else {
        smartCardContainerColor(baseThemeColor)
    }
    val variantColor =
        if (isSelected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val details = linkedMapOf<String, String>().apply {
        result.time?.let { put("来电时间", it) }
        result.location?.let { put("来电归属地", it) }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(
            1.dp,
            if (isSelected) containerColor else baseThemeColor.copy(alpha = 0.15f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.AutoMirrored.Rounded.PhoneCallback,
                iconTint = themeColor,
                iconBg = if (isSelected) {
                    MaterialTheme.colorScheme.inverseSurface
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                title = "来电提醒",
                titleColor = if (isSelected) {
                    MaterialTheme.colorScheme.inverseOnSurface
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                dividerColor = themeColor.copy(alpha = 0.1f),
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "来电号码",
                style = MaterialTheme.typography.bodyMedium,
                color = variantColor,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatPhoneNumber(result.phoneNumber),
                style = MaterialTheme.typography.displaySmallEmphasized.copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = themeColor,
            )

            if (details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                InfoMapList(
                    details = details,
                    containerColor = if (isSelected) {
                        baseThemeColor.copy(alpha = 0.1f)
                    } else {
                        baseThemeColor.copy(alpha = 0.08f)
                    },
                    labelColor = variantColor,
                    valueColor = if (isSelected) {
                        MaterialTheme.colorScheme.inverseOnSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    val dialIntent = Intent(
                        Intent.ACTION_DIAL,
                        "tel:${android.net.Uri.encode(result.phoneNumber)}".toUri(),
                    )
                    runCatching { context.startActivity(dialIntent) }
                },
                colors = if (isSelected) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                        contentColor = MaterialTheme.colorScheme.inverseSurface,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("一键回拨")
            }
        }
    }
}

private fun formatPhoneNumber(phoneNumber: String): String {
    val digits = phoneNumber.filter(Char::isDigit)
    return when {
        digits.length == 11 -> "${digits.take(3)} ${digits.substring(3, 7)} ${digits.takeLast(4)}"
        digits.length == 12 && digits.startsWith('0') -> {
            "${digits.take(4)} ${digits.substring(4, 8)} ${digits.takeLast(4)}"
        }
        else -> phoneNumber
    }
}
