package vip.mystery0.pixel.text.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.viewmodel.ArchivedConversationListUiState
import vip.mystery0.pixel.text.viewmodel.ArchivedConversationListViewModel

@Composable
fun ArchivedConversationListScreen(
    viewModel: ArchivedConversationListViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long, String) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var selectedThreadIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionMode = selectedThreadIds.isNotEmpty()

    LaunchedEffect(Unit) {
        viewModel.loadArchivedConversations()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedThreadIds = emptySet() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                title = {
                    Text(
                        if (selectionMode) "已选择 ${selectedThreadIds.size} 项" else "已归档会话",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                val selected = selectedThreadIds
                                selectedThreadIds = emptySet()
                                viewModel.unarchiveSelected(selected)
                            }
                        ) {
                            Icon(Icons.Rounded.Unarchive, contentDescription = "Unarchive")
                        }
                        IconButton(
                            onClick = {
                                Toast.makeText(context, "删除功能暂未实现", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                        }
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
            Box(modifier = Modifier.weight(1f)) {
                when (val state = uiState) {
                    is ArchivedConversationListUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }

                    is ArchivedConversationListUiState.Error -> {
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

                    is ArchivedConversationListUiState.Success -> {
                        val listState = rememberLazyListState()
                        var isRefreshing by remember { mutableStateOf(false) }

                        if (state.conversations.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "暂无归档会话",
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
                                viewModel.loadArchivedConversations(force = true)
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
                                    val selected = conversation.threadId in selectedThreadIds
                                    ConversationItem(
                                        conversation = conversation,
                                        selected = selected,
                                        onClick = {
                                            if (selectionMode) {
                                                selectedThreadIds =
                                                    if (selected) selectedThreadIds - conversation.threadId
                                                    else selectedThreadIds + conversation.threadId
                                            } else {
                                                onNavigateToDetail(
                                                    conversation.threadId,
                                                    conversation.address
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            selectedThreadIds =
                                                selectedThreadIds + conversation.threadId
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
}
