package vip.mystery0.pixel.text.ui.message.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R

@Composable
fun SearchSelectionBar(
    state: SearchSelectionState,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear, enabled = !state.isDeleting) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.msg_search_exit_selection_cd))
            }
            Text(
                text = if (state.isDeleting) {
                    stringResource(R.string.msg_search_deleting)
                } else {
                    stringResource(R.string.msg_search_selected_count, state.selectedIds.size)
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onSelectAll, enabled = !state.isDeleting) {
                Text(
                    if (allSelected) {
                        stringResource(R.string.msg_search_deselect_all)
                    } else {
                        stringResource(R.string.msg_search_select_all_results)
                    }
                )
            }
            IconButton(onClick = onDelete, enabled = !state.isDeleting && state.selectedIds.isNotEmpty()) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.msg_search_delete_selected_cd))
            }
        }
        if (state.isDeleting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun SearchDeleteDialog(
    snapshot: SearchDeleteSnapshot,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_search_delete_dialog_title)) },
        text = {
            val baseText = stringResource(R.string.msg_search_delete_dialog_body, snapshot.messageIds.size)
            val incompleteText = if (snapshot.incomplete) {
                "\n\n" + stringResource(R.string.msg_search_delete_dialog_incomplete)
            } else {
                ""
            }
            Text(baseText + incompleteText)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.msg_action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.msg_action_cancel)) }
        },
    )
}
