package vip.mystery0.pixel.text.ui.message.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.search.MessageSearchFilter
import vip.mystery0.pixel.text.domain.model.search.SearchDate

@Composable
fun SearchFilterBar(
    filter: MessageSearchFilter,
    simDisplayNameMap: Map<Int, String>,
    onPhoneClick: () -> Unit,
    onSimClick: () -> Unit,
    onTransportClick: () -> Unit,
    onDateClick: () -> Unit,
    onUnreadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 发件人号码
        val hasPhone = !filter.phoneNumber.isNullOrBlank()
        val phoneLabel = when {
            !filter.phoneDisplayName.isNullOrBlank() -> filter.phoneDisplayName
            hasPhone -> filter.phoneNumber
            else -> "发件人号码"
        }
        FilterChip(
            selected = hasPhone,
            onClick = onPhoneClick,
            label = {
                Text(
                    text = phoneLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.semantics {
                contentDescription = "发件人号码筛选，当前：$phoneLabel"
            }
        )

        // 2. SIM 卡
        val hasSim = filter.simSubIds.isNotEmpty()
        val simLabel = when {
            filter.simSubIds.isEmpty() -> "SIM 卡"
            filter.simSubIds.size == 1 -> {
                val subId = filter.simSubIds.first()
                simDisplayNameMap[subId] ?: "卡 $subId"
            }
            else -> "SIM 卡 · ${filter.simSubIds.size}"
        }
        FilterChip(
            selected = hasSim,
            onClick = onSimClick,
            label = {
                Text(
                    text = simLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.semantics {
                contentDescription = "SIM卡筛选，当前：$simLabel"
            }
        )

        // 3. 结果类型
        val hasTransport = filter.effectiveTransports.isNotEmpty()
        val transportLabel = when {
            filter.transports.size == 1 && filter.transports.contains(MessageTransport.SMS) -> "短信"
            filter.transports.size == 1 && filter.transports.contains(MessageTransport.MMS) -> "彩信"
            else -> "结果类型"
        }
        FilterChip(
            selected = hasTransport,
            onClick = onTransportClick,
            label = {
                Text(
                    text = transportLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.semantics {
                contentDescription = "结果类型筛选，当前：$transportLabel"
            }
        )

        // 4. 日期
        val hasDate = filter.date != SearchDate.ANY
        val dateLabel = if (filter.date == SearchDate.ANY) "日期" else filter.date.label
        FilterChip(
            selected = hasDate,
            onClick = onDateClick,
            label = {
                Text(
                    text = dateLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.semantics {
                contentDescription = "日期筛选，当前：$dateLabel"
            }
        )

        // 5. 未读（无箭头，直接切换）
        FilterChip(
            selected = filter.unreadOnly,
            onClick = onUnreadClick,
            label = {
                Text(
                    text = "未读",
                    maxLines = 1,
                )
            },
            modifier = Modifier.semantics {
                contentDescription = if (filter.unreadOnly) "已开启未读筛选" else "未开启未读筛选"
            }
        )
    }
}
