package vip.mystery0.pixel.text.ui.message.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import vip.mystery0.pixel.text.ui.component.AppEmptyState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
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
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    var draftSelection by rememberSaveable { mutableStateOf(currentSimSubIds) }

    val nameCounts = remember(activeSims) {
        activeSims.groupingBy { it.displayName }.eachCount()
    }

    @Composable
    fun getSimTitle(sim: SimInfo): String {
        return if ((nameCounts[sim.displayName] ?: 0) > 1) {
            stringResource(R.string.msg_search_sim_slot, sim.displayName, sim.slotIndex + 1)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.msg_search_select_sim),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when {
                activeSims.isEmpty() -> {
                    AppEmptyState(text = stringResource(R.string.msg_search_no_sim))
                    if (currentSimSubIds.isNotEmpty()) {
                        TextButton(onClick = { onClear(); onDismissRequest() }) {
                            Text(stringResource(R.string.msg_search_clear_sim))
                        }
                    }
                }
                activeSims.size == 1 -> {
                    Text(getSimTitle(activeSims.first()), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { onClear(); onDismissRequest() }) { Text(stringResource(R.string.msg_action_done)) }
                }
                activeSims.size == 2 -> {
                    Column(Modifier.selectableGroup()) {
                        val unrestricted = currentSimSubIds.isEmpty() ||
                            currentSimSubIds.containsAll(activeSims.map { it.subscriptionId })
                        SimFilterOption(stringResource(R.string.msg_search_any_sim), unrestricted) {
                            onClear()
                            onDismissRequest()
                        }
                        activeSims.forEach { sim ->
                            SimFilterOption(getSimTitle(sim), !unrestricted && sim.subscriptionId in currentSimSubIds) {
                                onApply(setOf(sim.subscriptionId))
                                onDismissRequest()
                            }
                        }
                    }
                }
                else -> {
                    activeSims.forEach { sim ->
                        val checked = sim.subscriptionId in draftSelection
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = {
                                    draftSelection = if (it) draftSelection + sim.subscriptionId
                                        else draftSelection - sim.subscriptionId
                                },
                            ).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(getSimTitle(sim))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onClear(); onDismissRequest() }) { Text(stringResource(R.string.msg_search_any_sim)) }
                        Button(onClick = { onApply(draftSelection); onDismissRequest() }) { Text(stringResource(R.string.msg_action_apply)) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SimFilterOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportFilterSheet(
    currentTransports: Set<MessageTransport>,
    onDismissRequest: () -> Unit,
    onApply: (Set<MessageTransport>) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val selected = currentTransports.singleOrNull()
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                .navigationBarsPadding().selectableGroup(),
        ) {
            Text(stringResource(R.string.msg_search_filter_type), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp))
            listOf(
                null to stringResource(R.string.msg_search_all_messages),
                MessageTransport.SMS to stringResource(R.string.msg_search_transport_sms),
                MessageTransport.MMS to stringResource(R.string.msg_search_transport_mms),
            )
                .forEach { (transport, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = selected == transport, role = Role.RadioButton, onClick = {
                                onApply(transport?.let { setOf(it) } ?: emptySet())
                                onDismissRequest()
                            }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == transport, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            Spacer(Modifier.height(16.dp))
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
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))

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
                text = stringResource(R.string.msg_search_filter_by_date),
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
                        text = stringResource(date.localizedLabelRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
