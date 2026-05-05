package vip.mystery0.pixel.text.ui.mock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.ui.message.cards.OriginalTextCard
import vip.mystery0.pixel.text.ui.message.factory.MessageCardFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockMessageScreen() {
    val mockMessages = listOf(
        MessageModel(
            id = 1,
            sender = "12306",
            content = "【铁路客服】订单E123456789，张三您已购2026-03-31的G6921次05车厢16D二等座，成都东16:14开往西安北。检票口A25。请戴好口罩，配合防疫工作。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 2, // 2 mins ago
            simName = "中国移动"
        ),
        MessageModel(
            id = 2,
            sender = "95588",
            content = "【工商银行】您正在进行快捷支付绑卡，验证码：884512，请勿泄露给他人。5分钟内有效。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 5,
            simName = "卡2"
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
            timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 48
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
    var showOriginal by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (showOriginal || message.parsedResult is ParsedResult.None) {
            OriginalTextCard(content = message.content)
        } else {
            MessageCardFactory.CreateCard(
                content = message.content,
                parsedResult = message.parsedResult
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
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
                        painter = androidx.compose.ui.res.painterResource(id = vip.mystery0.pixel.text.R.drawable.ic_sim),
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

            if (message.parsedResult !is ParsedResult.None) {
                Text(
                    text = if (showOriginal) "显示智能卡片" else "显示原文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showOriginal = !showOriginal }
                )
            }
        }
    }
}

fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 1000 * 60 -> "刚刚"
        diff < 1000 * 60 * 60 -> "${diff / (1000 * 60)}分钟前"
        diff < 1000 * 60 * 60 * 24 -> "${diff / (1000 * 60 * 60)}小时前"
        else -> "${diff / (1000 * 60 * 60 * 24)}天前"
    }
}
