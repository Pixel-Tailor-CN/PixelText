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
import androidx.compose.ui.unit.dp

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
                Icon(Icons.Rounded.Close, contentDescription = "退出多选")
            }
            Text(
                text = if (state.isDeleting) "正在删除…" else "已选 ${state.selectedIds.size} 条",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onSelectAll, enabled = !state.isDeleting) {
                Text(if (allSelected) "取消全选" else "全选当前结果")
            }
            IconButton(onClick = onDelete, enabled = !state.isDeleting && state.selectedIds.isNotEmpty()) {
                Icon(Icons.Rounded.Delete, contentDescription = "删除所选消息")
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
        title = { Text("删除所选消息？") },
        text = {
            Text(buildString {
                append("将永久删除所选 ${snapshot.messageIds.size} 条短信或彩信，无法恢复。不会删除同一会话中未选中的消息。")
                if (snapshot.incomplete) {
                    append("\n\n搜索结果可能不完整，本次仅删除已选中的消息，不包含后续出现的结果。")
                }
            })
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
