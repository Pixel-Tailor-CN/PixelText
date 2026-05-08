package vip.mystery0.pixel.text.ui.message.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.mystery0.pixel.text.domain.model.ParsedResult

@Composable
fun ExpressDeliveryCard(result: ParsedResult.ExpressDelivery, isSelected: Boolean = false) {
    val themeColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.tertiary
    val containerColor = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surface
    val onContainerColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
    val onContainerVariantColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    val headerBgColor = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.tertiaryContainer

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, if (isSelected) containerColor else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Column(modifier = Modifier.padding(16.dp)) {
                CardHeader(
                    icon = Icons.Rounded.Inventory,
                    iconTint = themeColor,
                    iconBg = headerBgColor,
                    title = "${result.company} · 取件通知",
                    dividerColor = if (isSelected) themeColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outlineVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = result.company,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainerVariantColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = result.code,
                        style = MaterialTheme.typography.displayMediumEmphasized.copy(
                            fontSize = 32.sp,
                            letterSpacing = 1.sp
                        ),
                        color = onContainerColor
                    )
                }
            }

            Surface(
                color = if (isSelected) themeColor.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.LocationOn,
                            contentDescription = "Location",
                            tint = onContainerVariantColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = result.location,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainerColor
                        )
                    }

                    if (result.time != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = "Time",
                                tint = onContainerVariantColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = result.time,
                                style = MaterialTheme.typography.bodyMedium,
                                color = onContainerColor
                            )
                        }
                    }
                }
            }
        }
    }
}