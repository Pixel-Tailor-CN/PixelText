package vip.mystery0.pixel.text.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.ui.createDefaultSmsAppRequestIntent
import vip.mystery0.pixel.text.ui.isDefaultSmsApp
import vip.mystery0.pixel.text.ui.theme.getAvatarColor
import vip.mystery0.pixel.text.util.SimInfoProvider
import vip.mystery0.pixel.text.viewmodel.ConversationListUiState
import vip.mystery0.pixel.text.viewmodel.ConversationListViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

private const val WRITE_SMS_PERMISSION = "android.permission.WRITE_SMS"

@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel = koinViewModel(),
    onNavigateToDetail: (Long, String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToMock: () -> Unit,
    onNavigateToArchive: () -> Unit,
    onNavigateToSpam: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasContactPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasRequestedStartupPermissions by remember { mutableStateOf(false) }
    var hasLoadedConversationsAfterPermission by remember { mutableStateOf(false) }
    var selectedThreadIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selectionMode = selectedThreadIds.isNotEmpty()
    var isDefaultSmsRoleHeld by remember(context) {
        mutableStateOf(context.isDefaultSmsApp())
    }
    val hasAllStartupPermissions =
        hasPermission && hasContactPermission && hasNotificationPermission

    val startupPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.READ_SMS)
            add(WRITE_SMS_PERMISSION)
            add(Manifest.permission.READ_CONTACTS)
        }.toTypedArray()
    }

    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun missingStartupPermissions(): Array<String> {
        return startupPermissions
            .filterNot(::isPermissionGranted)
            .toTypedArray()
    }

    fun refreshPermissionState() {
        hasPermission = isPermissionGranted(Manifest.permission.READ_SMS)
        hasContactPermission = isPermissionGranted(Manifest.permission.READ_CONTACTS)
        hasNotificationPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
    }

    val startupPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            refreshPermissionState()
            hasLoadedConversationsAfterPermission = false
        }
    )
    val defaultSmsAppLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultSmsRoleHeld = context.isDefaultSmsApp()
    }

    LaunchedEffect(hasRequestedStartupPermissions, hasAllStartupPermissions) {
        selectedThreadIds = emptySet()
        if (!hasRequestedStartupPermissions && !hasAllStartupPermissions) {
            val missingPermissions = missingStartupPermissions()
            if (missingPermissions.isNotEmpty()) {
                hasRequestedStartupPermissions = true
                startupPermissionLauncher.launch(missingPermissions)
            }
        }
    }

    LaunchedEffect(
        hasPermission,
        hasLoadedConversationsAfterPermission
    ) {
        if (!hasPermission) return@LaunchedEffect
        if (!hasLoadedConversationsAfterPermission) {
            hasLoadedConversationsAfterPermission = true
            viewModel.loadConversations(force = true)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hasPermission, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefaultSmsRoleHeld = context.isDefaultSmsApp()
                val currentHasPermission = isPermissionGranted(Manifest.permission.READ_SMS)
                if (currentHasPermission != hasPermission) {
                    if (!currentHasPermission) {
                        hasLoadedConversationsAfterPermission = false
                    }
                }
                refreshPermissionState()
                if (currentHasPermission) {
                    viewModel.refreshSilent()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(context, hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose {}

        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                viewModel.refreshSilent()
            }
        }

        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            contentObserver
        )
        context.contentResolver.registerContentObserver(
            Telephony.Mms.CONTENT_URI,
            true,
            contentObserver
        )

        onDispose {
            context.contentResolver.unregisterContentObserver(contentObserver)
        }
    }

    val uiState by viewModel.uiState.collectAsState()

    val sheetState = rememberModalBottomSheetState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showMenuSheet by remember { mutableStateOf(false) }
    var showNewChatSheet by remember { mutableStateOf(false) }
    var deleteCandidateThreadIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (selectionMode) {
                            IconButton(onClick = { selectedThreadIds = emptySet() }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                            }
                        }
                    },
                    title = {
                        Text(
                            text = when {
                                selectionMode -> "已选择 ${selectedThreadIds.size} 项"
                                else -> stringResource(id = R.string.app_name)
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        if (selectionMode) {
                            IconButton(
                                onClick = {
                                    val selected = selectedThreadIds
                                    selectedThreadIds = emptySet()
                                    val conversations =
                                        (uiState as? ConversationListUiState.Success)
                                            ?.conversations
                                            .orEmpty()
                                            .filter { it.threadId in selected }
                                    viewModel.archiveSelected(conversations)
                                }
                            ) {
                                Icon(Icons.Rounded.Archive, contentDescription = "Archive")
                            }
                            IconButton(
                                onClick = {
                                    deleteCandidateThreadIds = selectedThreadIds
                                }
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                            }
                        } else {
                            IconButton(onClick = onNavigateToSearch) {
                                Icon(Icons.Rounded.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { showMenuSheet = true }) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "More options"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            floatingActionButton = {
                if (hasPermission && !selectionMode) {
                    Column {
                        ExtendedFloatingActionButton(
                            text = { Text("Start chat") },
                            icon = {
                                Icon(
                                    Icons.Rounded.ChatBubbleOutline,
                                    contentDescription = null
                                )
                            },
                            onClick = { showNewChatSheet = true },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
        ) { paddingValues ->
            if (!hasPermission) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("需要读取短信权限", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = {
                                val missingPermissions = missingStartupPermissions()
                                if (missingPermissions.isNotEmpty()) {
                                    hasRequestedStartupPermissions = true
                                    startupPermissionLauncher.launch(missingPermissions)
                                }
                            }
                        ) {
                            Text("授予权限")
                        }
                    }
                }
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    when (val state = uiState) {
                        is ConversationListUiState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        }

                        is ConversationListUiState.Error -> {
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

                        is ConversationListUiState.Success -> {
                            val listState = rememberLazyListState()
                            var isRefreshing by remember { mutableStateOf(false) }

                            if (state.conversations.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "暂无会话",
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
                                    viewModel.loadConversations(force = true)
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                LaunchedEffect(state) {
                                    isRefreshing = false
                                }

                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 80.dp)
                                ) {
                                    items(
                                        state.conversations,
                                        key = { it.threadId }) { conversation ->
                                        val dismissState = rememberSwipeToDismissBoxState()
                                        val selected = conversation.threadId in selectedThreadIds
                                        LaunchedEffect(dismissState.targetValue) {
                                            if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                                                viewModel.markAsRead(conversation.threadId)
                                                dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                                            }
                                        }

                                        SwipeToDismissBox(
                                            state = dismissState,
                                            modifier = Modifier.animateItem(),
                                            backgroundContent = {
                                                SwipeBackground(dismissState)
                                            },
                                            enableDismissFromStartToEnd = conversation.unreadCount > 0 && !selectionMode,
                                            enableDismissFromEndToStart = false
                                        ) {
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

    if (deleteCandidateThreadIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteCandidateThreadIds = emptySet() },
            title = { Text("删除会话？") },
            text = { Text("将删除所选 ${deleteCandidateThreadIds.size} 个会话中的短信和彩信。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = deleteCandidateThreadIds
                        deleteCandidateThreadIds = emptySet()
                        selectedThreadIds = emptySet()
                        viewModel.hidePendingDelete(selected)
                        coroutineScope.launch {
                            val dismissJob = launch {
                                delay(3000.milliseconds)
                                snackbarHostState.currentSnackbarData?.dismiss()
                            }
                            val result = snackbarHostState.showSnackbar(
                                message = "已删除 ${selected.size} 个会话",
                                actionLabel = "恢复",
                                duration = SnackbarDuration.Indefinite
                            )
                            dismissJob.cancel()
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.restorePendingDelete(selected)
                            } else {
                                viewModel.deleteSelected(selected)
                            }
                        }
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidateThreadIds = emptySet() }) {
                    Text("取消")
                }
            }
        )
    }

    if (showMenuSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMenuSheet = false },
            sheetState = sheetState
        ) {
            MenuSheetContent(
                isDefaultSmsApp = isDefaultSmsRoleHeld,
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
                onSetDefaultSmsAppClicked = {
                    if (!isDefaultSmsRoleHeld) {
                        showMenuSheet = false
                        defaultSmsAppLauncher.launch(context.createDefaultSmsAppRequestIntent())
                    }
                }
            )
        }
    }

    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            NewChatBottomSheet(
                onDismiss = { showNewChatSheet = false },
                onNavigateToDetail = onNavigateToDetail
            )
        }
    }
}

@Composable
fun ConversationItem(
    modifier: Modifier = Modifier,
    conversation: ConversationModel,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val title = conversation.displayName?.takeIf { it.isNotBlank() } ?: conversation.address

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else getAvatarColor(conversation.address)
                    ),
                contentAlignment = Alignment.Center
            ) {
                // 使用联系人头像图标
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            if (conversation.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .align(Alignment.TopEnd)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = if (conversation.unreadCount > 0) MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    ) else MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatTimeShort(conversation.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.hasMms) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = "彩信",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = if (conversation.isMms) "[多媒体信息] ${conversation.snippet}".trim()
                    else conversation.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (conversation.unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun MenuSheetContent(
    isDefaultSmsApp: Boolean,
    onMockClicked: () -> Unit,
    onArchiveClicked: () -> Unit,
    onSpamClicked: () -> Unit,
    onSetDefaultSmsAppClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        ListItem(
            headlineContent = { Text("设置默认短信应用") },
            leadingContent = {
                Icon(
                    Icons.AutoMirrored.Rounded.Message,
                    contentDescription = null
                )
            },
            modifier = Modifier
                .alpha(if (isDefaultSmsApp) 0.38f else 1f)
                .clickable(enabled = !isDefaultSmsApp) { onSetDefaultSmsAppClicked() }
        )
        ListItem(
            headlineContent = { Text("归档短信") },
            leadingContent = { Icon(Icons.Rounded.Archive, contentDescription = null) },
            modifier = Modifier.clickable { onArchiveClicked() }
        )
        ListItem(
            headlineContent = { Text("骚扰与拦截") },
            leadingContent = { Icon(Icons.Rounded.Security, contentDescription = null) },
            modifier = Modifier.clickable { onSpamClicked() }
        )
        ListItem(
            headlineContent = { Text("设置") },
            leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            modifier = Modifier.clickable { }
        )
        ListItem(
            headlineContent = { Text("开发测试 (Mock)") },
            leadingContent = {
                Icon(
                    Icons.Rounded.Build,
                    contentDescription = null
                )
            },
            modifier = Modifier.clickable { onMockClicked() }
        )
        Spacer(
            modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
        )
    }
}

fun formatTimeShort(timestamp: Long): String {
    val instant = Instant.ofEpochMilli(timestamp)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    val now = LocalDateTime.now()

    val midnightToday = now.toLocalDate().atStartOfDay()
    val midnightThisWeek = midnightToday.minusDays(6)

    return when {
        // 今天：显示时间
        dateTime.isAfter(midnightToday) -> {
            dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        // 本周：显示星期
        dateTime.isAfter(midnightThisWeek) -> {
            val dayOfWeek = dateTime.dayOfWeek.value // 1 (Mon) to 7 (Sun)
            val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            days[dayOfWeek - 1]
        }
        // 今年：显示月日
        dateTime.year == now.year -> {
            dateTime.format(DateTimeFormatter.ofPattern("MM月dd日"))
        }
        // 去年及更早：显示年月日
        else -> {
            dateTime.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
        }
    }
}

@Composable
fun SwipeBackground(dismissState: SwipeToDismissBoxState) {
    val color = when (dismissState.dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val alignment = Alignment.CenterStart
    val icon = Icons.Rounded.DoneAll
    val scale by animateFloatAsState(
        if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) 0.75f else 1.2f
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        Icon(
            icon,
            contentDescription = "Mark as read",
            modifier = Modifier.scale(scale),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
fun NewChatBottomSheet(
    onDismiss: () -> Unit,
    onNavigateToDetail: (Long, String) -> Unit
) {
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var selectedSimSlot by remember { mutableIntStateOf(0) }

    val simCards = remember {
        SimInfoProvider.getActiveSimList(context)
    }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "需要联系人权限才能选择联系人", Toast.LENGTH_SHORT).show()
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        uri?.let {
            val contactId = context.contentResolver.query(
                it,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }

            contactId?.let { id ->
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id.toString()),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val number = cursor.getString(0)
                        val threadId = getThreadIdForAddress(context, number)
                        onDismiss()
                        onNavigateToDetail(threadId, number)
                    }
                }
            }
        }
    }

    val isValidPhoneNumber = phoneNumber.isNotBlank() && phoneNumber.matches(Regex("^[0-9+\\s-]+$"))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "接收人",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    TextField(
                        value = phoneNumber,
                        onValueChange = {
                            phoneNumber = it
                            showError = it.isNotBlank() && !it.matches(Regex("^[0-9+\\s-]+$"))
                        },
                        placeholder = { Text("输入电话号码") },
                        isError = showError,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent
                        )
                    )
                }
                if (showError) {
                    Text(
                        "请输入有效的电话号码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }

                if (simCards.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        simCards.forEachIndexed { index, sim ->
                            FilterChip(
                                selected = selectedSimSlot == index,
                                onClick = { selectedSimSlot = index },
                                label = { Text(sim.displayName) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                if (isValidPhoneNumber) {
                    val trimmedNumber = phoneNumber.trim()
                    val threadId = getThreadIdForAddress(context, trimmedNumber)
                    onDismiss()
                    onNavigateToDetail(threadId, trimmedNumber)
                }
            },
            enabled = isValidPhoneNumber,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("下一步")
        }

        OutlinedButton(
            onClick = {
                if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    contactPickerLauncher.launch(null)
                } else {
                    contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("选择联系人")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun getThreadIdForAddress(context: Context, address: String): Long {
    context.contentResolver.query(
        Telephony.Sms.CONTENT_URI,
        arrayOf(Telephony.Sms.THREAD_ID),
        "${Telephony.Sms.ADDRESS} = ?",
        arrayOf(address),
        "${Telephony.Sms.DATE} DESC LIMIT 1"
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getLong(0)
        }
    }
    return -1L
}
