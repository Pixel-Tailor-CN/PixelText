package vip.mystery0.pixel.text.ui.screen

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
import androidx.compose.material.icons.rounded.Security
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
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.viewmodel.SpamConversationListUiState
import vip.mystery0.pixel.text.viewmodel.SpamConversationListViewModel

@Composable
fun SpamConversationListScreen(
    viewModel: SpamConversationListViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

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
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
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
}
