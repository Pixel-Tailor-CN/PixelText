package vip.mystery0.pixel.text.ui.screen

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.VerificationCodeIndexModel
import vip.mystery0.pixel.text.ui.ObserveListScrollDirection
import vip.mystery0.pixel.text.viewmodel.VerificationCodeEvent
import vip.mystery0.pixel.text.viewmodel.VerificationCodeViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun VerificationCodeScreen(
    onNavigateToConversation: (Long, String) -> Unit,
    onScrollDirectionChanged: (isScrollingDown: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VerificationCodeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VerificationCodeEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    ObserveListScrollDirection(listState, onScrollDirectionChanged = onScrollDirectionChanged)
    LaunchedEffect(listState, state.canLoadMore) {
        if (!state.canLoadMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (
                    lastVisible != null &&
                    lastVisible >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
                ) {
                    viewModel.loadNextMonth()
                }
            }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("验证码") },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("重新识别全部短信") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                viewModel.rebuildAll()
                            },
                            enabled = !state.isRefreshing && !state.isRebuilding,
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing || state.isRebuilding,
            onRefresh = {
                if (!state.isRefreshing && !state.isRebuilding) viewModel.refresh()
            },
            modifier = Modifier.padding(padding),
        ) {
            when {
                state.isInitializing -> CenterMessage("正在识别验证码…", true)
                state.errorMessage != null && state.pages.isEmpty() -> CenterMessage(state.errorMessage!!, false)
                state.pages.isEmpty() -> CenterMessage("暂无验证码", false)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.pages.forEach { page ->
                        stickyHeader(key = "header_${page.month.monthKey}") {
                            Surface(color = MaterialTheme.colorScheme.surface) {
                                Text(
                                    text = formatMonth(page.month.monthKey),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                )
                            }
                        }
                        items(page.messages, key = { it.messageId }) { message ->
                            VerificationIndexCard(
                                message = message,
                                expanded = message.messageId in state.expandedMessageIds,
                                body = state.messageBodies[message.messageId],
                                loadingBody = message.messageId in state.loadingBodies,
                                onToggle = { viewModel.toggleMessageMode(message.messageId) },
                                onNavigate = { onNavigateToConversation(message.threadId, message.address) },
                            )
                        }
                    }
                }
            }
            if (state.isRebuilding && state.pages.isNotEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun VerificationIndexCard(
    message: VerificationCodeIndexModel,
    expanded: Boolean,
    body: String?,
    loadingBody: Boolean,
    onToggle: () -> Unit,
    onNavigate: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(message.signature ?: message.address, style = MaterialTheme.typography.titleMedium)
                    Text(formatMessageTime(message.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onNavigate) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "进入会话")
                }
            }
            if (expanded) {
                when {
                    loadingBody -> CircularProgressIndicator()
                    body != null -> Text(body, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        clipboard.setText(AnnotatedString(message.code))
                    },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message.code, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "复制验证码")
                }
            }
            TextButton(onClick = onToggle, enabled = !loadingBody) {
                Text(if (expanded) "显示验证码" else "显示原文")
            }
        }
    }
}

@Composable
private fun CenterMessage(message: String, loading: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator()
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMonth(monthKey: String): String = runCatching {
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMM")
    DateTimeFormatter.ofPattern(pattern, locale).format(YearMonth.parse(monthKey))
}.getOrDefault(monthKey)

private fun formatMessageTime(timestamp: Long): String {
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMdHm")
    return DateTimeFormatter.ofPattern(pattern, locale)
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
}

private const val LOAD_MORE_THRESHOLD = 4
