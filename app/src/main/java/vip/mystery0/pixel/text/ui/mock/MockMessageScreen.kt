package vip.mystery0.pixel.text.ui.mock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockMessageScreen() {
    val mockMessages = listOf(
        MessageModel(
            id = 1,
            sender = "12306",
            content = "【铁路客服】订单E123456789，用户您已购4月30日C6921次10车11B号广州南09:02开。请戴好口罩，配合防疫工作。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 2
        ),
        MessageModel(
            id = 2,
            sender = "95588",
            content = "【工商银行】您正在进行快捷支付绑卡，验证码：884512，请勿泄露给他人。5分钟内有效。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 5
        ),
        MessageModel(
            id = 3,
            sender = "10086",
            content = "【中国移动】尊敬的客户，您的本月套餐流量已使用80%，请注意使用。如需订购流量包，请回复...。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 24
        ),
        MessageModel(
            id = 4,
            sender = "Alipay",
            content = "Your Alipay verification code is 4392. Do not share it with anyone.",
            timestamp = System.currentTimeMillis()
        )
    ).map { it.copy(parsedResult = MessageParser.parse(it.content)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("原点短信 - 原型验证") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(mockMessages) { message ->
                MessageItem(message)
            }
        }
    }
}

@Composable
fun MessageItem(message: MessageModel) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message.sender,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp, start = 8.dp)
        )

        when (val result = message.parsedResult) {
            is ParsedResult.TrainTicket -> TrainTicketCard(message.content, result)
            is ParsedResult.VerificationCode -> VerificationCodeCard(message.content, result)
            is ParsedResult.None -> NormalMessageCard(message.content)
        }
    }
}

@Composable
fun NormalMessageCard(content: String) {
    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 280.dp)
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun VerificationCodeCard(content: String, result: ParsedResult.VerificationCode) {
    val clipboardManager = LocalClipboardManager.current

    ElevatedCard(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                onClick = {
                    clipboardManager.setText(AnnotatedString(result.code))
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "验证码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = result.code,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            letterSpacing = 4.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun TrainTicketCard(content: String, result: ParsedResult.TrainTicket) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Train,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "列车行程",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = result.departureStation,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = result.departureTime,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = result.trainNumber,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "------>",
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = result.arrivalStation ?: "未知",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f)
            )

            Text(
                text = "日期: ${result.date}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}
