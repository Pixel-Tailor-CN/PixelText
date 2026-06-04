package vip.mystery0.pixel.text.ui.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.MmsImageCard
import vip.mystery0.pixel.text.ui.message.cards.OriginalTextCard
import vip.mystery0.pixel.text.ui.message.cards.SpamMessageCard
import vip.mystery0.pixel.text.ui.message.factory.MessageCardFactory
import vip.mystery0.pixel.text.viewmodel.ManualSpamCheckState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageItem(
    message: MessageModel,
    isSelected: Boolean,
    textScale: Float,
    manualSpamCheckState: ManualSpamCheckState? = null,
    onCheckSpam: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    interactionEnabled: Boolean = true,
) {
    var showOriginal by remember { mutableStateOf(false) }
    val isSpam = message.spamScore >= 0.7f
    val interactionSource = remember { MutableInteractionSource() }

    val arrangement = if (message.isReceived) Arrangement.Start else Arrangement.End
    val cardAlignment = if (message.isReceived) Alignment.Start else Alignment.End

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = interactionEnabled,
                onClick = onClick,
                onLongClick = onLongClick,
                indication = null,
                interactionSource = interactionSource
            ),
        horizontalAlignment = cardAlignment
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(0.85f),
            contentAlignment = if (message.isReceived) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            Column(horizontalAlignment = cardAlignment) {
                if (message.imageUris.isNotEmpty()) {
                    MmsImageCard(imageUris = message.imageUris, isSelected = isSelected)
                    if (message.content.isNotBlank() || !message.mmsSubject.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                val hasTextContent =
                    message.content.isNotBlank() || !message.mmsSubject.isNullOrBlank()
                if (hasTextContent) {
                    if (showOriginal) {
                        OriginalTextCard(
                            content = message.content,
                            isSelected = isSelected,
                            subject = message.mmsSubject,
                            textScale = textScale
                        )
                    } else if (isSpam) {
                        SpamMessageCard(isSelected = isSelected)
                    } else if (message.parsedResult is ParsedResult.None) {
                        OriginalTextCard(
                            content = message.content,
                            isSelected = isSelected,
                            subject = message.mmsSubject,
                            textScale = textScale
                        )
                    } else {
                        MessageCardFactory.CreateCard(
                            content = message.content,
                            parsedResult = message.parsedResult,
                            isSelected = isSelected
                        )
                    }
                } else {
                    OriginalTextCard(
                        content = "【不支持的消息】",
                        isSelected = isSelected,
                        textScale = textScale,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = arrangement
        ) {
            Text(
                text = formatTimeAgo(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sim),
                        contentDescription = "SIM",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = message.simName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSpam || message.parsedResult !is ParsedResult.None) {
                Text(
                    text = if (showOriginal) "显示智能卡片" else "显示原文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showOriginal = !showOriginal }
                )
            }
        }

        if (message.content.isNotBlank() && manualSpamCheckState != null) {
            val manualCheckText = when (manualSpamCheckState) {
                ManualSpamCheckState.Checking -> "识别中..."
                is ManualSpamCheckState.Result -> {
                    val percent = (manualSpamCheckState.score * 100).toInt().coerceIn(0, 100)
                    if (manualSpamCheckState.score >= 0.7f) {
                        "手动识别: 骚扰概率 $percent%"
                    } else {
                        "手动识别: 风险较低 $percent%"
                    }
                }

                is ManualSpamCheckState.Error -> manualSpamCheckState.message
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(top = 4.dp),
                horizontalArrangement = arrangement
            ) {
                Text(
                    text = manualCheckText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (manualSpamCheckState) {
                        is ManualSpamCheckState.Error -> MaterialTheme.colorScheme.error
                        is ManualSpamCheckState.Result ->
                            if (manualSpamCheckState.score >= 0.7f) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }

                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.clickable(
                        enabled = manualSpamCheckState is ManualSpamCheckState.Error,
                        onClick = onCheckSpam
                    )
                )
            }
        }
    }
}

fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val oneMinute = 1000L * 60
    val oneHour = oneMinute * 60
    val oneDay = oneHour * 24
    val sevenDays = oneDay * 7

    return when {
        diff < oneMinute * 5 -> "刚刚"
        diff < oneHour -> "${diff / oneMinute}分钟前"
        diff < oneDay -> "${diff / oneHour}小时前"
        diff < sevenDays -> "${diff / oneDay}天前"
        else -> {
            val formatter =
                SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.getDefault())
            formatter.format(Date(timestamp))
        }
    }
}
