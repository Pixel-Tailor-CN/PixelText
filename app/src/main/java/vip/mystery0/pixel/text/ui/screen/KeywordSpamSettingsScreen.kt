package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.BlockedKeyword
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
            item(key = "privacy") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "规则仅保存在此设备",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "只匹配短信正文，不会上传关键词、短信内容或匹配结果。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Key,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("还没有拦截关键词", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "添加后，正文包含关键词的短信会被标记为骚扰短信。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    KeywordCard(
                        keyword = keyword,
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
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
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
            Text(
                if (running) "正在后台检查现有短信，你可以继续编辑关键词。"
                else "旧的匹配结果已保留，可以重新尝试更新。",
                style = MaterialTheme.typography.bodyMedium,
            )
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
private fun KeywordCard(
    keyword: BlockedKeyword,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(keyword.keyword, style = MaterialTheme.typography.titleMedium)
                Text(
                    "包含此内容的短信会被标记为骚扰",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
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
    AlertDialog(
        onDismissRequest = onDismiss,
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
                    placeholder = { Text("例如：账单提醒") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                HorizontalDivider()
                Text(
                    "包含该关键词的短信将被标记为骚扰短信，并按照当前骚扰短信配置处理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(input) },
                enabled = saveState != KeywordSaveUiState.Saving,
            ) { Text(if (saveState == KeywordSaveUiState.Saving) "保存中" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
