package vip.mystery0.pixel.text.ui.message.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.search.SearchDate
import vip.mystery0.pixel.text.util.SimInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimFilterSheet(
    currentSimSubIds: Set<Int>,
    activeSims: List<SimInfo>,
    onDismissRequest: () -> Unit,
    onApply: (Set<Int>) -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draftSelection by rememberSaveable { mutableStateOf(currentSimSubIds) }

    val nameCounts = remember(activeSims) {
        activeSims.groupingBy { it.displayName }.eachCount()
    }

    fun getSimTitle(sim: SimInfo): String {
        return if ((nameCounts[sim.displayName] ?: 0) > 1) {
            "${sim.displayName} (卡槽 ${sim.slotIndex + 1})"
        } else {
            sim.displayName
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "选择 SIM 卡",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "不选或全选均表示不限 SIM 卡，包括历史和未知 SIM 卡，应用后不会标记为已筛选。只有一张 SIM 卡时，选与不选效果相同。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (activeSims.isEmpty()) {
                Text(
                    text = "暂无可用的 SIM 卡信息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                if (currentSimSubIds.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            onClear()
                            onDismissRequest()
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("清除已有 SIM 卡筛选")
                    }
                }
            } else {
                activeSims.forEach { sim ->
                    val isChecked = draftSelection.contains(sim.subscriptionId)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                draftSelection = if (isChecked) {
                                    draftSelection - sim.subscriptionId
                                } else {
                                    draftSelection + sim.subscriptionId
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                draftSelection = if (checked) {
                                    draftSelection + sim.subscriptionId
                                } else {
                                    draftSelection - sim.subscriptionId
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = getSimTitle(sim),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        draftSelection = emptySet()
                    }
                ) {
                    Text("重置")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onApply(draftSelection)
                        onDismissRequest()
                    }
                ) {
                    Text("应用")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportFilterSheet(
    currentTransports: Set<MessageTransport>,
    onDismissRequest: () -> Unit,
    onApply: (Set<MessageTransport>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draftSelection by rememberSaveable { mutableStateOf(currentTransports) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "结果类型",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "不选或全选均表示不限类型，同时搜索短信和彩信。只有单选一种类型时，才会标记为已筛选。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val options = listOf(
                MessageTransport.SMS to "短信",
                MessageTransport.MMS to "彩信",
            )

            options.forEach { (transport, label) ->
                val isChecked = draftSelection.contains(transport)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            draftSelection = if (isChecked) {
                                draftSelection - transport
                            } else {
                                draftSelection + transport
                            }
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { checked ->
                            draftSelection = if (checked) {
                                draftSelection + transport
                            } else {
                                draftSelection - transport
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        draftSelection = emptySet()
                    }
                ) {
                    Text("重置")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onApply(draftSelection)
                        onDismissRequest()
                    }
                ) {
                    Text("应用")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateFilterSheet(
    currentDate: SearchDate,
    onDismissRequest: () -> Unit,
    onDateSelected: (SearchDate) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "按日期筛选",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SearchDate.entries.forEach { date ->
                val isSelected = currentDate == date
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDateSelected(date)
                            onDismissRequest()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            onDateSelected(date)
                            onDismissRequest()
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = date.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
