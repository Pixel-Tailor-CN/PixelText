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
import androidx.compose.material3.HorizontalDivider
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("关键词拦截") },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Rounded.Info, contentDescription = "关于关键词拦截")
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
                text = { Text("添加关键词") },
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
                        text = "还没有拦截关键词",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            } else {
                item(key = "count") {
                    Text(
                        "已添加 ${keywords.size} 个关键词",
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
            title = { Text("关键词拦截") },
            text = { Text("正文包含关键词的短信会被标记为骚扰。规则仅保存在本机，不上传关键词、短信内容或匹配结果。") },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } },
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
            title = { Text("删除关键词？") },
            text = { Text("删除“${keyword.keyword}”后，将重新检查历史短信的骚扰状态。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(keyword)
                        deletingKeyword = null
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingKeyword = null }) { Text("取消") }
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
                if (running) "正在更新匹配结果" else "匹配结果更新失败",
                style = MaterialTheme.typography.titleSmall,
            )
            if (!running) {
                Text("已保留上次匹配结果", style = MaterialTheme.typography.bodyMedium)
            }
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("重试")
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
        title = { Text(if (keyword == null) "添加拦截关键词" else "编辑拦截关键词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        onInputChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("关键词或短语") },
                    placeholder = { Text("例如：免费领券") },
                    singleLine = true,
                    enabled = !saving,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                HorizontalDivider()
                Text(
                    "正文包含此内容的短信将按骚扰短信处理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(input) },
                enabled = !saving && input.isNotBlank(),
            ) { Text(if (saveState == KeywordSaveUiState.Saving) "保存中" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
