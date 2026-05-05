package vip.mystery0.pixel.text.ui.mock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.ui.message.factory.MessageCardFactory

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
    )

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

        MessageCardFactory.CreateCard(content = message.content)
    }
}
