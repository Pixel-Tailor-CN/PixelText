package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.BlockedKeyword
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.ui.component.AppEmptyState
import vip.mystery0.pixel.text.ui.component.RuleItemCard
import vip.mystery0.pixel.text.viewmodel.KeywordRebuildUiState
import vip.mystery0.pixel.text.viewmodel.KeywordSaveUiState
import vip.mystery0.pixel.text.viewmodel.KeywordSpamViewModel

@Composable
fun KeywordSpamSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: KeywordSpamViewModel = koinViewModel(),
) {
    val keywords by viewModel.keywords.collectAsState()
    val rebuildState by viewModel.rebuildState.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingKeyword by remember { mutableStateOf<BlockedKeyword?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var deletingKeyword by remember { mutableStateOf<BlockedKeyword?>(null) }

    LaunchedEffect(saveState) {
        if (saveState == KeywordSaveUiState.Saved) {
            showEditor = false
            editingKeyword = null
            viewModel.resetSaveState()
        }
    }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.settings_action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_keyword_blocking_title)) },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = stringResource(R.string.settings_keyword_about_cd),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.navigationBarsPadding(),
                onClick = {
                    editingKeyword = null
                    viewModel.resetSaveState()
                    showEditor = true
                },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.settings_keyword_add)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = paddingValues.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (rebuildState) {
                KeywordRebuildUiState.Running -> item(key = "rebuilding") {
                    RebuildStatusCard(running = true, onRetry = {})
                }

                KeywordRebuildUiState.Failed -> item(key = "rebuild_failed") {
                    RebuildStatusCard(running = false, onRetry = viewModel::retryRebuild)
                }

                KeywordRebuildUiState.Idle -> Unit
            }

            if (keywords.isEmpty()) {
                item(key = "empty") {
                    AppEmptyState(
                        imageRes = R.drawable.illustration_spam_conversation_empty,
                        text = stringResource(R.string.settings_keyword_empty),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            } else {
                item(key = "count") {
                    Text(
                        stringResource(
                            if (keywords.size == 1) R.string.settings_keyword_added_count_single
                            else R.string.settings_keyword_added_count_plural,
                            keywords.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(keywords, key = BlockedKeyword::id) { keyword ->
                    RuleItemCard(
                        title = keyword.keyword,
                        subtitle = "",
                        onEdit = {
                            editingKeyword = keyword
                            viewModel.resetSaveState()
                            showEditor = true
                        },
                        onDelete = { deletingKeyword = keyword },
                    )
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.settings_keyword_blocking_title)) },
            text = { Text(stringResource(R.string.settings_keyword_about_body)) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.settings_action_ok)) } },
        )
    }

    if (showEditor) {
        KeywordEditorDialog(
            keyword = editingKeyword,
            saveState = saveState,
            onDismiss = {
                showEditor = false
                editingKeyword = null
                viewModel.resetSaveState()
            },
            onSave = { viewModel.save(editingKeyword?.id, it) },
            onInputChanged = viewModel::resetSaveState,
        )
    }

    deletingKeyword?.let { keyword ->
        AlertDialog(
            onDismissRequest = { deletingKeyword = null },
            title = { Text(stringResource(R.string.settings_keyword_delete_title)) },
            text = { Text(stringResource(R.string.settings_keyword_delete_body, keyword.keyword)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(keyword)
                        deletingKeyword = null
                    }
                ) { Text(stringResource(R.string.settings_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingKeyword = null }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }
}

@Composable
private fun RebuildStatusCard(running: Boolean, onRetry: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (running) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(
                    if (running) R.string.settings_keyword_rebuild_running
                    else R.string.settings_keyword_rebuild_failed,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!running) {
                Text(stringResource(R.string.settings_keyword_rebuild_kept), style = MaterialTheme.typography.bodyMedium)
            }
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_action_retry))
                }
            }
        }
    }
}

@Composable
private fun KeywordEditorDialog(
    keyword: BlockedKeyword?,
    saveState: KeywordSaveUiState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onInputChanged: () -> Unit,
) {
    var input by remember(keyword?.id) { mutableStateOf(keyword?.keyword.orEmpty()) }
    val error = (saveState as? KeywordSaveUiState.Error)?.message
    val saving = saveState == KeywordSaveUiState.Saving
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (keyword == null) R.string.settings_keyword_editor_add_title
                    else R.string.settings_keyword_editor_edit_title,
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    onInputChanged()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_keyword_editor_label)) },
                singleLine = true,
                enabled = !saving,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(input) },
                enabled = !saving && input.isNotBlank(),
            ) {
                Text(
                    stringResource(
                        if (saveState == KeywordSaveUiState.Saving) R.string.settings_action_saving
                        else R.string.settings_action_save,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}
