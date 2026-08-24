package vip.mystery0.pixel.text.ui.screen.mock

import androidx.compose.foundation.isSystemInDarkTheme
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
import vip.mystery0.pixel.text.domain.theme.ThemeConfiguration
import vip.mystery0.pixel.text.domain.theme.ThemeMode
import vip.mystery0.pixel.text.ui.message.MessageItem
import vip.mystery0.pixel.text.ui.theme.resolveConversationDetailStyle

@Composable
fun MockMessageScreen(
    messageFactory: MockMessageFactory = koinInject(),
) {
    val mockMessages = remember(messageFactory) {
        messageFactory.create(MOCK_SMS_MESSAGES)
    }
    val mode = if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT
    val detailStyle = resolveConversationDetailStyle(
        configuration = ThemeConfiguration(),
        mode = mode,
        colorScheme = MaterialTheme.colorScheme,
        highTextContrastEnabled = false,
    )

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
                MessageItem(
                    message = message,
                    isSelected = false,
                    textScale = detailStyle.textScale,
                    originalMessageStyle = detailStyle.originalMessage,
                    showSimInfo = detailStyle.showSimInfo,
                    onClick = {},
                    onLongClick = {},
                )
            }
            item {
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

private val MOCK_SMS_MESSAGES = listOf(
    """【联通助理】未接听
主叫:00000000000
归属:四川
时间:2026-07-12 12:32
5G智能通信服务，尽在联通助理微信公众号""",
    "【中国联通】流量用尽提醒：截至08月07日09时42分，“限量流量”部分国内通用流量已用尽；您“达量限速”流量已使用96.4MB，超过40.000GB后将被限速。当前套餐套外流量资费为1元/GB日租宝，达到3元当日流量不再计费（当日有效，自动叠加）。具体资费及使用情况请登录中国联通APP或拨打10010客服热线查询。退订此类短信提醒请回复TDYJDX，退订此类电话提醒请回复TDYJDH。",
    "【流量不足预警】截至08月06日13时20分，“限量流量”部分：国内通用流量已用845.7MB，剩余178.3MB；您“达量限速”流量已使用5.0MB，超过40.000GB后将被限速；使用情况请登录中国联通APP或拨打10010客服热线查询。【中国联通】",
    "【剩余流量查询】尊敬的客户，您好！您本月套餐内流量共20G，已使用5.77G。目前国内通用流量剩余14.23G。\n1、如需了解本机套餐余量、账单及已开通业务等详情，请点击 https://dx.10086.cn/A/Example?sid=DESENSITIZED 。【中国移动】",
    "844868(登录随机码) ，感谢您使用中国联通营业厅微信小程序【中国联通】",
    "您的信用卡9352于2026年03月27日消费RMB222.02元，因卡片过期导致交易失败【中国银行】",
    "您的借记卡0307于0328发生双信息代收交易，金额209.99人民币，已冻结31.01美元。【中国银行】",
    "【12306】张三购票成功，4月30日C1234次，广州南站09:02开。详情点击s.12306.cn/s/A/aBcDeF",
    "【菜鸟驿站】您的中通包裹已到5栋1单元101号无人自助店，请23:00前凭22-3-5041扫码开门自助取件。",
    "【招商银行】您账户0252于04月10日14:22入账工资，人民币14131.58。存款 cmbt.cn/xdcx",
    "【招商银行】您账户0252于04月11日12:30银联扣款人民币1068.00元（银联在线支付，银联转账（云闪付）-张三）",
    "【汇联易甄选】购票成功，2026-04-02 14:24:00出发，西安北—成都东，G2345，检票口：23B，张三 二等座 08车厢05A号。服务热线：4006297878",
)
