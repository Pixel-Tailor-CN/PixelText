package vip.mystery0.pixel.text.ui.message.cards.carrier

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.mystery0.pixel.text.domain.model.DataUsageStatus
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.CardHeader
import vip.mystery0.pixel.text.ui.message.cards.InfoMapList

@Composable
fun DataUsageCard(result: ParsedResult.DataUsage, isSelected: Boolean = false) {
    val baseThemeColor = when (result.status) {
        DataUsageStatus.NORMAL,
        DataUsageStatus.LOW -> MaterialTheme.colorScheme.tertiary
        DataUsageStatus.EXHAUSTED -> MaterialTheme.colorScheme.error
    }
    val themeColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else baseThemeColor
    val containerColor =
        if (isSelected) MaterialTheme.colorScheme.inverseSurface else baseThemeColor.copy(alpha = 0.03f)
    val variantColor =
        if (isSelected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val details = linkedMapOf<String, String>().apply {
        result.cutoffTime?.let { put("截止时间", it) }
        putAll(result.details)
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
                icon = Icons.Rounded.DataUsage,
                iconTint = themeColor,
                iconBg = if (isSelected) {
                    MaterialTheme.colorScheme.inverseSurface
                } else {
                    baseThemeColor.copy(alpha = 0.12f)
                },
                title = when (result.status) {
                    DataUsageStatus.NORMAL -> "流量提醒"
                    DataUsageStatus.LOW -> "流量不足预警"
                    DataUsageStatus.EXHAUSTED -> "流量用尽提醒"
                },
                titleColor = if (isSelected) {
                    MaterialTheme.colorScheme.inverseOnSurface
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                dividerColor = themeColor.copy(alpha = 0.1f),
            )

            Spacer(modifier = Modifier.height(24.dp))
            if (result.status == DataUsageStatus.EXHAUSTED) {
                Text(
                    text = "已用尽",
                    style = MaterialTheme.typography.displayMediumEmphasized.copy(fontSize = 36.sp),
                    color = themeColor,
                )
            } else {
                DataAmount(
                    data = result.primaryData.orEmpty(),
                    valueColor = themeColor,
                    unitColor = variantColor,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = result.dataLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = variantColor,
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
        }
    }
}

@Composable
private fun DataAmount(
    data: String,
    valueColor: androidx.compose.ui.graphics.Color,
    unitColor: androidx.compose.ui.graphics.Color,
) {
    val displayData = data.trim().ifBlank { "--" }
    val match = DATA_AMOUNT_REGEX.matchEntire(displayData)
    val value = match?.groupValues?.get(1) ?: displayData
    val unit = match?.groupValues?.get(2).orEmpty()

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            style = MaterialTheme.typography.displayMediumEmphasized.copy(fontSize = 36.sp),
            color = valueColor,
        )
        if (unit.isNotEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = unitColor,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

private val DATA_AMOUNT_REGEX = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-z]+)$")
