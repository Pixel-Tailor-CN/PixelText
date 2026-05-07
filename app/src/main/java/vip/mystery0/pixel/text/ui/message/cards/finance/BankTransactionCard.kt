package vip.mystery0.pixel.text.ui.message.cards.finance

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
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.CardHeader
import vip.mystery0.pixel.text.ui.message.cards.InfoMapList

@Composable
fun BankTransactionCard(result: ParsedResult.BankTransaction, isSelected: Boolean = false) {
    val baseThemeColor = if (result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val themeColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else baseThemeColor
    val headerColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else (if (result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
    
    val containerColor = if (isSelected) MaterialTheme.colorScheme.inverseSurface else baseThemeColor.copy(alpha = 0.03f)
    val onContainerColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
    val onContainerVariantColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, if (isSelected) containerColor else baseThemeColor.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Rounded.AccountBalance,
                iconTint = headerColor,
                iconBg = if (isSelected) MaterialTheme.colorScheme.inverseSurface else (if (result.isSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                title = "银行交易",
                dividerColor = if (isSelected) themeColor.copy(alpha = 0.1f) else baseThemeColor.copy(alpha = 0.1f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = result.type,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainerVariantColor
                    )
                    
                    if (!result.isSuccess) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "交易失败",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = result.amount,
                    style = MaterialTheme.typography.displaySmallEmphasized.copy(
                        fontSize = 28.sp,
                        textDecoration = if (!result.isSuccess) TextDecoration.LineThrough else null
                    ),
                    color = themeColor
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            val finalDetails = result.details.toMutableMap()
            if (!result.isSuccess && result.errorMessage != null) {
                finalDetails["失败原因"] = result.errorMessage
            }

            InfoMapList(
                details = finalDetails,
                highlightKey = if (!result.isSuccess) "失败原因" else null,
                highlightColor = MaterialTheme.colorScheme.error,
                containerColor = if (isSelected) baseThemeColor.copy(alpha = 0.1f) else baseThemeColor.copy(alpha = 0.08f)
            )
        }
    }
}