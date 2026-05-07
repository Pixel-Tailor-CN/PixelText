package vip.mystery0.pixel.text.ui.message.cards.ticket

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.CardHeader
import vip.mystery0.pixel.text.ui.message.cards.DashedDivider

@Composable
fun FlightCard(result: ParsedResult.Ticket.Flight, isSelected: Boolean = false) {
    val baseThemeColor = MaterialTheme.colorScheme.primary
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.inverseSurface else baseThemeColor.copy(alpha = 0.03f),
        animationSpec = tween(durationMillis = 200),
        label = "containerColor"
    )
    val onContainerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 200),
        label = "onContainerColor"
    )
    val onContainerVariantColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 200),
        label = "onContainerVariantColor"
    )
    val accentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else baseThemeColor,
        animationSpec = tween(durationMillis = 200),
        label = "accentColor"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, if (isSelected) containerColor else baseThemeColor.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Rounded.FlightTakeoff,
                iconTint = accentColor,
                iconBg = if (isSelected) onContainerColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.primaryContainer,
                title = "航班行程",
                dividerColor = onContainerColor.copy(alpha = 0.1f)
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
                Text(
                    text = result.flightNumber,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = accentColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = result.departureCode,
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(
                            fontSize = 36.sp
                        ),
                        color = onContainerColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${result.departureCity} ${result.departureTime}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainerVariantColor
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        HorizontalDivider(
                            color = accentColor.copy(alpha = 0.2f),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Rounded.AirplanemodeActive,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(16.dp)
                        )
                        HorizontalDivider(
                            color = accentColor.copy(alpha = 0.2f),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = result.flightType,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor.copy(alpha = 0.7f)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = result.arrivalCode,
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(
                            fontSize = 36.sp
                        ),
                        color = onContainerColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${result.arrivalCity} ${result.arrivalTime}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainerVariantColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            DashedDivider(color = accentColor.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "航站楼",
                        style = MaterialTheme.typography.labelSmall,
                        color = onContainerVariantColor.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = result.terminal,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = onContainerColor
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(30.dp)
                        .background(accentColor.copy(alpha = 0.2f))
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "登机时间",
                        style = MaterialTheme.typography.labelSmall,
                        color = onContainerVariantColor.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = result.boardingTime,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = accentColor
                    )
                }
            }
        }
    }
}
