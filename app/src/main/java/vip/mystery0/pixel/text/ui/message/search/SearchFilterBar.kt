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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
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
            else -> stringResource(R.string.msg_search_filter_sender)
        }
        val phoneCd = stringResource(R.string.msg_search_filter_sender_cd, phoneLabel)
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
                contentDescription = phoneCd
            }
        )

        // 2. SIM 卡
        val hasSim = filter.simSubIds.isNotEmpty()
        val simLabel = when {
            filter.simSubIds.isEmpty() -> stringResource(R.string.msg_search_filter_sim)
            filter.simSubIds.size == 1 -> {
                val subId = filter.simSubIds.first()
                simDisplayNameMap[subId] ?: stringResource(R.string.msg_search_sim_fallback, subId)
            }
            else -> stringResource(R.string.msg_search_filter_sim_count, filter.simSubIds.size)
        }
        val simCd = stringResource(R.string.msg_search_filter_sim_cd, simLabel)
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
                contentDescription = simCd
            }
        )

        // 3. 结果类型
        val hasTransport = filter.effectiveTransports.isNotEmpty()
        val transportLabel = stringResource(
            when (filter.transports.singleOrNull()) {
                MessageTransport.SMS -> R.string.msg_search_transport_sms
                MessageTransport.MMS -> R.string.msg_search_transport_mms
                else -> R.string.msg_search_filter_type
            }
        )
        val transportCd = stringResource(R.string.msg_search_filter_type_cd, transportLabel)
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
                contentDescription = transportCd
            }
        )

        // 4. 日期
        val hasDate = filter.date != SearchDate.ANY
        val dateLabel = if (filter.date == SearchDate.ANY) {
            stringResource(R.string.msg_search_filter_date)
        } else {
            stringResource(filter.date.localizedLabelRes())
        }
        val dateCd = stringResource(R.string.msg_search_filter_date_cd, dateLabel)
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
                contentDescription = dateCd
            }
        )

        // 5. 未读（无箭头，直接切换）
        val unreadCd = stringResource(
            if (filter.unreadOnly) R.string.msg_search_filter_unread_on_cd
            else R.string.msg_search_filter_unread_off_cd
        )
        FilterChip(
            selected = filter.unreadOnly,
            onClick = onUnreadClick,
            label = {
                Text(
                    text = stringResource(R.string.msg_search_filter_unread),
                    maxLines = 1,
                )
            },
            modifier = Modifier.semantics {
                contentDescription = unreadCd
            }
        )
    }
}

internal fun SearchDate.localizedLabelRes(): Int = when (this) {
    SearchDate.ANY -> R.string.msg_search_date_any
    SearchDate.WEEK -> R.string.msg_search_date_week
    SearchDate.MONTH -> R.string.msg_search_date_month
    SearchDate.HALF_YEAR -> R.string.msg_search_date_half_year
    SearchDate.YEAR -> R.string.msg_search_date_year
}
