package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.viewmodel.HistorySpamScanProgress
import vip.mystery0.pixel.text.viewmodel.HistorySpamStatsUiState
import vip.mystery0.pixel.text.viewmodel.SpamConversationListUiState
import vip.mystery0.pixel.text.viewmodel.SpamConversationListViewModel

@Composable
fun SpamConversationListScreen(
    viewModel: SpamConversationListViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val historyStatsState by viewModel.historyStatsState.collectAsState()
    val scanProgress by viewModel.historyScanProgress.collectAsState()
    val isCompletedScanProgressHidden by viewModel.isCompletedScanProgressHidden.collectAsState()
    var showHistoryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadSpamConversations()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        "骚扰与拦截",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            showHistoryDialog = true
                            viewModel.loadHistoryStats()
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = "历史短信识别"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            scanProgress
                ?.takeUnless { it.isCompleted && isCompletedScanProgressHidden }
                ?.let { progress ->
                    HistoricalSpamScanProgressBanner(
                        progress = progress,
                        onHide = viewModel::hideCompletedScanProgress
                    )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (val state = uiState) {
                    is SpamConversationListUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }

                    is SpamConversationListUiState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "读取失败: ${state.message}",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    is SpamConversationListUiState.Success -> {
                        val listState = rememberLazyListState()
                        var isRefreshing by remember { mutableStateOf(false) }

                        if (state.conversations.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "暂无已识别骚扰会话",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            return@Box
                        }

                        val shouldLoadMore = remember {
                            derivedStateOf {
                                val lastVisibleItem =
                                    listState.layoutInfo.visibleItemsInfo.lastOrNull()
                                        ?: return@derivedStateOf false
                                lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 5
                            }
                        }

                        LaunchedEffect(shouldLoadMore.value) {
                            if (shouldLoadMore.value) {
                                viewModel.loadMore()
                            }
                        }

                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                isRefreshing = true
                                viewModel.loadSpamConversations(force = true)
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LaunchedEffect(state) {
                                isRefreshing = false
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(
                                    state.conversations,
                                    key = { it.threadId }
                                ) { conversation ->
                                    ConversationItem(
                                        conversation = conversation.copy(unreadCount = 0),
                                        modifier = Modifier.animateItem(),
                                        onClick = {
                                            onNavigateToDetail(
                                                conversation.threadId,
                                                conversation.address
                                            )
                                        }
                                    )
                                }
                                item {
                                    Spacer(
                                        modifier = Modifier.windowInsetsBottomHeight(
                                            WindowInsets.navigationBars
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHistoryDialog) {
        HistoricalSpamStatsDialog(
            state = historyStatsState,
            onDismiss = {
                showHistoryDialog = false
                viewModel.clearHistoryStats()
            },
            onStartScan = {
                showHistoryDialog = false
                viewModel.startHistoricalScan()
            }
        )
    }
}

@Composable
private fun HistoricalSpamScanProgressBanner(
    progress: HistorySpamScanProgress,
    onHide: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val title = when {
                progress.isCompleted -> "历史短信识别完成"
                progress.isFailed -> "历史短信识别失败"
                else -> "正在识别历史短信"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                if (progress.isCompleted) {
                    TextButton(onClick = onHide) {
                        Text("隐藏")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearWavyProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${progress.processed}/${progress.total} ${progress.percent}%",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun HistoricalSpamStatsDialog(
    state: HistorySpamStatsUiState,
    onDismiss: () -> Unit,
    onStartScan: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("历史短信识别") },
        text = {
            when (state) {
                HistorySpamStatsUiState.Idle,
                HistorySpamStatsUiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator()
                        Text(
                            text = "正在统计短信数据...",
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }

                is HistorySpamStatsUiState.Error -> {
                    Text("统计失败: ${state.message}")
                }

                is HistorySpamStatsUiState.Success -> {
                    val stats = state.stats
                    val message = buildString {
                        appendLine("短信总数量: ${stats.totalCount}")
                        appendLine("已识别数量: ${stats.identifiedCount}")
                        append("未识别数量: ${stats.pendingCount}")
                    }
                    Text(message)
                }
            }
        },
        confirmButton = {
            when (state) {
                is HistorySpamStatsUiState.Success -> {
                    if (state.stats.pendingCount > 0) {
                        TextButton(onClick = onStartScan) {
                            Text("开始扫描")
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text("知道了")
                        }
                    }
                }

                is HistorySpamStatsUiState.Error -> {
                    TextButton(onClick = onDismiss) {
                        Text("知道了")
                    }
                }

                else -> Unit
            }
        },
        dismissButton = {
            val canDismiss = state !is HistorySpamStatsUiState.Success ||
                state.stats.pendingCount > 0
            if (canDismiss) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}
