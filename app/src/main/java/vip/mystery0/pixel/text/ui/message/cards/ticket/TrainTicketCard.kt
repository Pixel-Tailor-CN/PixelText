package vip.mystery0.pixel.text.ui.message.cards.ticket

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DirectionsRailway
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.CardHeader
import vip.mystery0.pixel.text.ui.message.cards.DashedDivider

@Composable
fun TrainTicketCard(result: ParsedResult.Ticket.TrainTicket, isSelected: Boolean = false) {
    val themeColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.primary
    val containerColor = if (isSelected) MaterialTheme.colorScheme.inverseSurface else themeColor.copy(alpha = 0.03f)
    val onContainerColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
    val onContainerVariantColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, if (isSelected) containerColor else themeColor.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Rounded.DirectionsRailway,
                iconTint = themeColor,
                iconBg = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.primaryContainer,
                title = "火车票",
                dividerColor = if (isSelected) themeColor.copy(alpha = 0.1f) else themeColor.copy(alpha = 0.1f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = result.date,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainerVariantColor
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) themeColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = result.trainNumber,
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = if (isSelected) themeColor else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = result.departureTime,
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(
                            fontSize = 32.sp
                        ),
                        color = onContainerColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = result.departureStation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainerVariantColor
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = themeColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, themeColor.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = result.trainType,
                            style = MaterialTheme.typography.labelSmall,
                            color = themeColor.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = result.arrivalTime,
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(
                            fontSize = 32.sp
                        ),
                        color = onContainerColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = result.arrivalStation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainerVariantColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            DashedDivider(color = themeColor.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) themeColor.copy(alpha = 0.08f) else themeColor.copy(alpha = 0.08f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "乘车人",
                            style = MaterialTheme.typography.labelMedium,
                            color = onContainerVariantColor.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = result.passenger,
                            style = MaterialTheme.typography.bodyMediumEmphasized,
                            color = onContainerColor
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) themeColor.copy(alpha = 0.08f) else themeColor.copy(alpha = 0.08f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "座位",
                            style = MaterialTheme.typography.labelMedium,
                            color = onContainerVariantColor.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = result.seat,
                            style = MaterialTheme.typography.bodyMediumEmphasized,
                            color = onContainerColor
                        )
                    }
                }
            }
        }
    }
}
