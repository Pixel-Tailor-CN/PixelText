package vip.mystery0.pixel.text.ui.screen

import android.text.format.DateFormat
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import vip.mystery0.pixel.text.domain.model.VerificationCodeIndexModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.ObserveListScrollDirection
import vip.mystery0.pixel.text.ui.isDefaultSmsApp
import vip.mystery0.pixel.text.ui.message.cards.OriginalTextCard
import vip.mystery0.pixel.text.ui.message.cards.VerificationCodeCard
import vip.mystery0.pixel.text.util.isDebugModeEnabled
import vip.mystery0.pixel.text.viewmodel.ConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.MarkAllReadResultEvent
import vip.mystery0.pixel.text.viewmodel.VerificationCodeEvent
import vip.mystery0.pixel.text.viewmodel.VerificationCodeViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun VerificationCodeScreen(
    onNavigateToConversation: (Long, String, Long) -> Unit,
    onNavigateToMock: () -> Unit,
    onNavigateToArchive: () -> Unit,
    onNavigateToSpam: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onScrollDirectionChanged: (isScrollingDown: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VerificationCodeViewModel = koinViewModel(),
    conversationListViewModel: ConversationListViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenuSheet by remember { mutableStateOf(false) }
    val menuSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val context = LocalContext.current
    var accessRefreshKey by remember { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        accessRefreshKey++
        if (granted) viewModel.refresh()
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        accessRefreshKey++
        if (context.isDefaultSmsApp()) viewModel.refresh()
    }
    val hasReadSms = remember(accessRefreshKey) {
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }
    val isDefaultSms = remember(accessRefreshKey) {
        context.isDefaultSmsApp()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VerificationCodeEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    LaunchedEffect(conversationListViewModel) {
        conversationListViewModel.markAllReadResultEvents.collect { event ->
            val message = when (event) {
                is MarkAllReadResultEvent.Success ->
                    "已标记 ${event.conversationCount} 个会话为已读"

                MarkAllReadResultEvent.NoUnread -> "没有未读会话"
                is MarkAllReadResultEvent.Failure -> event.reason
            }
            snackbarHostState.showSnackbar(message)
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
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("验证码") },
                actions = {
                    IconButton(onClick = { showMenuSheet = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when {
                !hasReadSms -> CenterMessage(
                    message = "需要短信读取权限才能识别验证码",
                    loading = false,
                    actionLabel = "授予权限",
                    onAction = { permissionLauncher.launch(Manifest.permission.READ_SMS) },
                )

                !isDefaultSms -> CenterMessage(
                    message = "请将 Pixel Text 设为默认短信应用后重试",
                    loading = false,
                    actionLabel = "设为默认",
                    onAction = { requestDefaultSmsRole(context, roleLauncher::launch) },
                )

                state.isInitializing -> CenterMessage("正在识别验证码…", true)
                state.errorMessage != null && state.pages.isEmpty() -> CenterMessage(
                    message = state.errorMessage!!,
                    loading = false,
                    actionLabel = "检查设置",
                    onAction = onNavigateToSettings,
                )

                state.pages.isEmpty() -> CenterMessage("暂无验证码", false)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "navigation_bar_spacer") {
                        Spacer(
                            Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                        )
                    }
                    state.pages.forEach { page ->
                        items(page.messages, key = { it.messageId }) { message ->
                            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                VerificationIndexCard(
                                    message = message,
                                    expanded = message.messageId in state.expandedMessageIds,
                                    body = state.messageBodies[message.messageId],
                                    loadingBody = message.messageId in state.loadingBodies,
                                    onToggle = { viewModel.toggleMessageMode(message.messageId) },
                                    onNavigate = {
                                        onNavigateToConversation(
                                            message.threadId,
                                            message.address,
                                            message.messageId,
                                        )
                                    },
                                )
                            }
                        }
                        stickyHeader(key = "header_${page.month.monthKey}") {
                            Surface(color = MaterialTheme.colorScheme.surface) {
                                Text(
                                    text = formatMonth(page.month.monthKey),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (state.isRebuilding && state.pages.isNotEmpty()) {
                CircularProgressIndicator(Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp))
            }
        }
    }

    if (showMenuSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMenuSheet = false },
            sheetState = menuSheetState,
        ) {
            MenuSheetContent(
                isDefaultSmsApp = isDefaultSms,
                showDebugMenu = isDebugModeEnabled(),
                onMockClicked = {
                    showMenuSheet = false
                    onNavigateToMock()
                },
                onArchiveClicked = {
                    showMenuSheet = false
                    onNavigateToArchive()
                },
                onSpamClicked = {
                    showMenuSheet = false
                    onNavigateToSpam()
                },
                onMarkAllReadClicked = {
                    showMenuSheet = false
                    conversationListViewModel.markAllConversationsAsRead()
                },
                onSettingsClicked = {
                    showMenuSheet = false
                    onNavigateToSettings()
                },
                onSetDefaultSmsAppClicked = {
                    if (!isDefaultSms) {
                        showMenuSheet = false
                        requestDefaultSmsRole(context, roleLauncher::launch)
                    }
                },
            )
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
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                when {
                    expanded && loadingBody -> CircularProgressIndicator(Modifier.padding(20.dp))
                    expanded && body != null -> OriginalTextCard(content = body)
                    else -> VerificationCodeCard(
                        content = body.orEmpty(),
                        result = ParsedResult.VerificationCode(
                            code = message.code,
                            signature = message.signature ?: message.displayName ?: message.address,
                        ),
                        compactCopyButton = true,
                    )
                }
            }
            IconButton(onClick = onNavigate) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "进入会话")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatMessageTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (expanded) "显示验证码" else "显示原文",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = !loadingBody, onClick = onToggle),
            )
        }
    }
}

@Composable
private fun CenterMessage(
    message: String,
    loading: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) CircularProgressIndicator()
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun requestDefaultSmsRole(context: Context, launch: (android.content.Intent) -> Unit) {
    val roleManager = context.getSystemService(RoleManager::class.java)
    launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
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
