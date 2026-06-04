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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.MessageItem

@Composable
fun MockMessageScreen() {
    val mockMessages = listOf(
        MessageModel(
            id = 1,
            sender = "12306",
            content = "10月24日 周四 G123 08:00北京南开往上海虹桥，乘车人张三，座位05车厢 12F。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 2,
            simName = "中国移动",
            parsedResult = ParsedResult.Ticket.TrainTicket(
                date = "10月24日 周四",
                trainNumber = "G123",
                trainType = "高铁",
                departureTime = "08:00",
                departureStation = "北京南",
                arrivalTime = "12:35",
                arrivalStation = "上海虹桥",
                details = mapOf(
                    "乘车人" to "张三",
                    "座位" to "05车厢 12F"
                )
            )
        ),
        MessageModel(
            id = 2,
            sender = "中国国航",
            content = "11月12日周二CA1553，上海SHA 14:20直飞深圳SZX 16:55。航站楼T2，登机时间13:40。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 5,
            simName = "卡2",
            parsedResult = ParsedResult.Ticket.Flight(
                date = "11月12日 周二",
                flightNumber = "CA1553",
                departureCode = "SHA",
                departureCity = "上海",
                departureTime = "14:20",
                flightType = "直飞",
                arrivalCode = "SZX",
                arrivalCity = "深圳",
                arrivalTime = "16:55",
                terminal = "T2",
                boardingTime = "13:40"
            )
        ),
        MessageModel(
            id = 3,
            sender = "95588",
            content = "您尾号8892的账户发生POS消费，金额-125.50，余额￥14500.20。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 2,
            simName = "中国移动",
            parsedResult = ParsedResult.BankTransaction(
                type = "POS消费",
                amount = "-125.50",
                details = mapOf(
                    "账户" to "尾号 8892",
                    "余额" to "￥14500.20"
                )
            )
        ),
        MessageModel(
            id = 4,
            sender = "招商银行",
            content = "尾号8892账户10月25日消费失败，金额 192.10，失败原因：卡片已过期，请更换新卡后重试。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 3,
            simName = "中国移动",
            parsedResult = ParsedResult.BankTransaction(
                type = "消费",
                amount = "192.10",
                isSuccess = false,
                errorMessage = "卡片已过期",
                details = mapOf(
                    "账户" to "信用卡 尾号 8892"
                )
            )
        ),
        MessageModel(
            id = 5,
            sender = "菜鸟驿站",
            content = "【菜鸟驿站】中通快递暂存至5栋1单元101号无人自助店，凭提货码 22-3-5041 提取，请于今天 23:00 前领取。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 12,
            simName = "中国移动",
            parsedResult = ParsedResult.ExpressDelivery(
                company = "中通快递",
                code = "22-3-5041",
                location = "5栋1单元101号无人自助店",
                time = "今天 23:00"
            )
        ),
        MessageModel(
            id = 6,
            sender = "10086",
            content = "充值成功！充值号码138****5678，充值金额100.00元，当前余额￥153.20。",
            timestamp = System.currentTimeMillis() - 1000 * 60 * 60 * 24,
            simName = "卡1",
            parsedResult = ParsedResult.PhoneRecharge(
                amount = "100.00",
                details = mapOf(
                    "充值号码" to "138****5678",
                    "当前余额" to "￥153.20"
                )
            )
        )
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
                MessageItem(message, false, 1f, onClick = {}, onLongClick = {})
            }
            item {
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
