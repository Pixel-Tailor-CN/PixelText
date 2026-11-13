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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
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
        val previewEmptyText = stringResource(R.string.settings_whitelist_preview_empty)
        val previewMatchText = stringResource(R.string.settings_whitelist_preview_match)
        val previewNoMatchText = stringResource(R.string.settings_whitelist_preview_no_match)
        val preview = remember(editor.type, editor.input, editor.testSender, previewEmptyText, previewMatchText, previewNoMatchText) {
            if (editor.testSender.isEmpty()) null else {
                val values = if (editor.type == WhitelistRuleType.EXACT) {
                    editor.input.split('\n', ',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty)
                } else listOf(editor.input.trim())
                val error = values.firstNotNullOfOrNull { SenderWhitelistMatcher.validate(editor.type, it) }
                when {
                    values.isEmpty() -> previewEmptyText
                    error != null -> error
                    values.any { SenderWhitelistMatcher(editor.type, it).matches(editor.testSender) } -> previewMatchText
                    else -> previewNoMatchText
                }
            }
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = {
                Text(
                    stringResource(
                        if (editor.id == null) R.string.settings_whitelist_editor_add_title
                        else R.string.settings_whitelist_editor_edit_title,
                    ),
                )
            },
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
                        label = {
                            Text(
                                stringResource(
                                    if (editor.type == WhitelistRuleType.EXACT) R.string.settings_whitelist_label_number
                                    else R.string.settings_whitelist_label_pattern,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        minLines = if (editor.type == WhitelistRuleType.EXACT && editor.id == null) 2 else 1,
                        maxLines = 4,
                        isError = state.error != null,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (editor.type == WhitelistRuleType.EXACT) {
                                        if (editor.id == null) R.string.settings_whitelist_hint_exact_multi
                                        else R.string.settings_whitelist_hint_exact_single
                                    } else R.string.settings_whitelist_hint_regex,
                                ),
                            )
                        },
                    )
                    OutlinedTextField(
                        value = editor.testSender,
                        onValueChange = { viewModel.updateEditor(editor.copy(testSender = it.take(256))) },
                        label = { Text(stringResource(R.string.settings_whitelist_test_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        singleLine = true,
                    )
                    preview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        stringResource(R.string.settings_whitelist_save_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.settings_whitelist_saving), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::save, enabled = !state.busy && editor.input.isNotBlank()) {
                    Text(stringResource(R.string.settings_action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog, enabled = !state.busy) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }
    state.deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.settings_whitelist_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_whitelist_delete_body, rule.value))
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete, enabled = !state.busy) {
                Text(stringResource(R.string.settings_action_delete), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog, enabled = !state.busy) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }
}
