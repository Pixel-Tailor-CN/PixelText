package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistMatcher
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType
import vip.mystery0.pixel.text.viewmodel.SenderWhitelistViewModel
import vip.mystery0.pixel.text.viewmodel.WhitelistUiState

@Composable
fun SenderWhitelistDialogs(
    viewModel: SenderWhitelistViewModel,
    state: WhitelistUiState,
    snackbar: SnackbarHostState,
) {
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    state.editor?.let { editor ->
        val preview = remember(editor.type, editor.input, editor.testSender) {
            if (editor.testSender.isEmpty()) null else {
                val values = if (editor.type == WhitelistRuleType.EXACT) {
                    editor.input.split('\n', ',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty)
                } else listOf(editor.input.trim())
                val error = values.firstNotNullOfOrNull { SenderWhitelistMatcher.validate(editor.type, it) }
                when {
                    values.isEmpty() -> "请先输入规则"
                    error != null -> error
                    values.any { SenderWhitelistMatcher(editor.type, it).matches(editor.testSender) } -> "匹配：该号码将被放行"
                    else -> "不匹配"
                }
            }
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(if (editor.id == null) "添加白名单" else "编辑白名单") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WhitelistRuleType.entries.forEach { type ->
                            FilterChip(
                                selected = editor.type == type,
                                onClick = { viewModel.updateEditor(editor.copy(type = type)) },
                                enabled = !state.busy,
                                label = { Text(type.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = editor.input,
                        onValueChange = { viewModel.updateEditor(editor.copy(input = it)) },
                        label = { Text(if (editor.type == WhitelistRuleType.EXACT) "号码" else "表达式") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        minLines = if (editor.type == WhitelistRuleType.EXACT && editor.id == null) 2 else 1,
                        maxLines = 4,
                        isError = state.error != null,
                        supportingText = {
                            Text(
                                if (editor.type == WhitelistRuleType.EXACT) {
                                    if (editor.id == null) "保留 +86 等前缀，多个号码用换行或逗号分隔"
                                    else "完整匹配号码，保留 +86 等前缀"
                                } else "使用 RE2 整串匹配，例如 106.+；不支持环视或回溯引用",
                            )
                        },
                    )
                    OutlinedTextField(
                        value = editor.testSender,
                        onValueChange = { viewModel.updateEditor(editor.copy(testSender = it.take(256))) },
                        label = { Text("试匹配号码（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        singleLine = true,
                    )
                    preview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("保存会同时放行匹配的历史短信；之后修改或删除规则不会撤销已有放行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在保存并放行历史消息…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::save, enabled = !state.busy && editor.input.isNotBlank()) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog, enabled = !state.busy) { Text("取消") }
            },
        )
    }
    state.deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("删除白名单规则？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${rule.value}\n\n已放行的消息仍保持非骚扰。之后收到的消息若未命中其他规则，将恢复正常识别。")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete, enabled = !state.busy) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog, enabled = !state.busy) { Text("取消") } },
        )
    }
}
