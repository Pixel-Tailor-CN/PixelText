package vip.mystery0.pixel.text.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.ui.component.AppEmptyState
import vip.mystery0.pixel.text.ui.component.RuleItemCard
import vip.mystery0.pixel.text.viewmodel.SenderWhitelistViewModel

@Composable
fun SenderWhitelistScreen(
    onNavigateBack: () -> Unit,
    viewModel: SenderWhitelistViewModel = koinViewModel(),
) {
    val rules by viewModel.rules.collectAsState()
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showInfo by remember { mutableStateOf(false) }
    BackHandler(enabled = state.busy) { }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("号码白名单") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showInfo = true }, enabled = !state.busy) {
                        Icon(Icons.Rounded.Info, contentDescription = "关于号码白名单")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.navigationBarsPadding(),
                onClick = { if (!state.busy) viewModel.openEditor() },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("添加规则") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp, bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rules.isEmpty()) {
                item(key = "empty") {
                    AppEmptyState(
                        imageRes = R.drawable.illustration_spam_conversation_empty,
                        text = "暂无白名单规则",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            } else {
                item(key = "count") {
                    Text(
                        "已添加 ${rules.size} 条规则",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(rules, key = { it.id }) { rule ->
                RuleItemCard(
                    title = rule.value,
                    subtitle = rule.type.label,
                    onEdit = { viewModel.openEditor(rule) },
                    onDelete = { viewModel.requestDelete(rule) },
                    enabled = !state.busy,
                    titleMaxLines = 3,
                    editDescription = "编辑规则 ${rule.value}",
                    deleteDescription = "删除规则 ${rule.value}",
                )
            }
        }
    }
    SenderWhitelistDialogs(viewModel, state, snackbar)
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("号码白名单") },
            text = { Text("规则仅保存在本机。匹配的号码会放行，优先于模型和关键词识别。删除或修改规则不会撤销已有放行，也不会恢复已删除的短信。") },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } },
        )
    }
}

/** 会话里的快捷添加也复用编辑器和历史放行确认，不直接静默写入规则。 */
@Composable
fun ConversationWhitelistAction(
    sender: String,
    snackbar: SnackbarHostState,
    viewModel: SenderWhitelistViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    IconButton(onClick = { viewModel.openEditor(sender = sender) }, enabled = sender.isNotBlank() && !state.busy) {
        Icon(Icons.Rounded.VerifiedUser, contentDescription = "添加到骚扰识别白名单")
    }
    SenderWhitelistDialogs(viewModel, state, snackbar)
}
