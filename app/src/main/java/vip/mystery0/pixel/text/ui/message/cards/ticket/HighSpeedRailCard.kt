package vip.mystery0.pixel.text.ui.message.cards.ticket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.model.ParsedResult

@Composable
fun HighSpeedRailCard(result: ParsedResult.Ticket.HighSpeedRail) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Title
            Text(
                text = "火车票购票成功",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Stations and Times
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Departure
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = result.departureStation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = result.departureTime,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.W500),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                // Center icon
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "G",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                }

                // Arrival
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = result.arrivalStation ?: "--",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = result.arrivalTime ?: "--",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.W500),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Details list
            val formattedTime =
                if (result.date.contains("-")) "${result.date} ${result.departureTime}:00" else "${result.date} ${result.departureTime}"
            DetailRow("发车时间", formattedTime, isLink = true)
            DetailRow("座位", result.seat ?: "--")
            DetailRow("乘客人", result.passenger ?: "--")
            DetailRow("检票口", result.ticketGate ?: "--")
            DetailRow("状态", result.status ?: "--")
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, isLink: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.width(70.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
    }
}
