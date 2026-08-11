package vip.mystery0.pixel.text.ui.screen.mock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.ui.message.MessageItem

@Composable
fun MockMessageScreen(
    messageFactory: MockMessageFactory = koinInject(),
) {
    val mockMessages = remember(messageFactory) {
        messageFactory.create(MOCK_SMS_MESSAGES)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(mockMessages) { message ->
                MessageItem(message, false, 1f, onClick = {}, onLongClick = {})
            }
            item {
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

private val MOCK_SMS_MESSAGES = listOf(
    "844868(登录随机码) ，感谢您使用中国联通营业厅微信小程序【中国联通】",
    "您的信用卡9352于2026年03月27日消费RMB222.02元，因卡片过期导致交易失败【中国银行】",
    "您的借记卡0307于0328发生双信息代收交易，金额209.99人民币，已冻结31.01美元。【中国银行】",
    "【12306】张三购票成功，4月30日C1234次，广州南站09:02开。详情点击s.12306.cn/s/A/aBcDeF",
    "【菜鸟驿站】您的中通包裹已到5栋1单元101号无人自助店，请23:00前凭22-3-5041扫码开门自助取件。",
    "【招商银行】您账户0252于04月10日14:22入账工资，人民币14131.58。存款 cmbt.cn/xdcx",
    "【招商银行】您账户0252于04月11日12:30银联扣款人民币1068.00元（银联在线支付，银联转账（云闪付）-张三）",
    "【汇联易甄选】购票成功，2026-04-02 14:24:00出发，西安北—成都东，G2345，检票口：23B，张三 二等座 08车厢05A号。服务热线：4006297878",
)
