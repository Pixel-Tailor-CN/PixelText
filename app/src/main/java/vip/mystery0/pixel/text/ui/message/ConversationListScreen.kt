package vip.mystery0.pixel.text.ui.message

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.ConversationModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel = koinViewModel(),
    onNavigateToDetail: (Long, String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToMock: () -> Unit
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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasPermission = isGranted
            if (isGranted) viewModel.loadConversations()
        }
    )

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadConversations()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hasPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (hasPermission) {
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
    var showProfileSheet by remember { mutableStateOf(false) }
    var showNewChatSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(id = R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSearch) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp, start = 8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { showProfileSheet = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "P",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            floatingActionButton = {
                if (hasPermission) {
                    ExtendedFloatingActionButton(
                        text = { Text("Start chat") },
                        icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null) },
                        onClick = { showNewChatSheet = true },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            if (!hasPermission) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("需要读取短信权限", style = MaterialTheme.typography.bodyLarge)
                }
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
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
                                        LaunchedEffect(dismissState.targetValue) {
                                            if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                                                viewModel.markAsRead(conversation.threadId)
                                                dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                                            }
                                        }

                                        SwipeToDismissBox(
                                            state = dismissState,
                                            backgroundContent = {
                                                SwipeBackground(dismissState)
                                            },
                                            enableDismissFromStartToEnd = conversation.unreadCount > 0,
                                            enableDismissFromEndToStart = false
                                        ) {
                                            ConversationItem(
                                                conversation = conversation,
                                                onClick = {
                                                    onNavigateToDetail(
                                                        conversation.threadId,
                                                        conversation.address
                                                    )
                                                }
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
    }

    if (showProfileSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProfileSheet = false },
            sheetState = sheetState
        ) {
            ProfileSheetContent(
                onMockClicked = {
                    showProfileSheet = false
                    onNavigateToMock()
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
    conversation: ConversationModel,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(vip.mystery0.pixel.text.ui.theme.getAvatarColor(conversation.address)),
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
                    text = conversation.address,
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
fun ProfileSheetContent(onMockClicked: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "P",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.headlineMedium
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Pixel Text User", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))

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
        ListItem(
            headlineContent = { Text("归档短信") },
            leadingContent = { Icon(Icons.Rounded.Archive, contentDescription = null) },
            modifier = Modifier.clickable { }
        )
        ListItem(
            headlineContent = { Text("骚扰与拦截") },
            leadingContent = { Icon(Icons.Rounded.Security, contentDescription = null) },
            modifier = Modifier.clickable { }
        )
        ListItem(
            headlineContent = { Text("设置") },
            leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            modifier = Modifier.clickable { }
        )
        Spacer(modifier = Modifier.height(32.dp))
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
    var selectedSimSlot by remember { mutableStateOf(0) }

    val simCards = remember {
        getAvailableSimCards(context)
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
                    androidx.compose.material3.TextField(
                        value = phoneNumber,
                        onValueChange = {
                            phoneNumber = it
                            showError = it.isNotBlank() && !it.matches(Regex("^[0-9+\\s-]+$"))
                        },
                        placeholder = { Text("输入电话号码") },
                        isError = showError,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
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
                        simCards.forEachIndexed { index, simName ->
                            androidx.compose.material3.FilterChip(
                                selected = selectedSimSlot == index,
                                onClick = { selectedSimSlot = index },
                                label = { Text(simName) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        androidx.compose.material3.Button(
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

        androidx.compose.material3.OutlinedButton(
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

private fun getAvailableSimCards(context: Context): List<String> {
    val simCards = mutableListOf<String>()
    try {
        context.contentResolver.query(
            "content://telephony/siminfo".toUri(),
            arrayOf("display_name", "carrier_name"),
            null,
            null,
            null
        )?.use { cursor ->
            val displayNameIdx = cursor.getColumnIndex("display_name")
            val carrierNameIdx = cursor.getColumnIndex("carrier_name")
            var index = 1
            while (cursor.moveToNext()) {
                val displayName =
                    if (displayNameIdx >= 0) cursor.getString(displayNameIdx) else null
                val carrierName =
                    if (carrierNameIdx >= 0) cursor.getString(carrierNameIdx) else null
                val name = when {
                    !carrierName.isNullOrBlank() && carrierName != "null" -> carrierName
                    !displayName.isNullOrBlank() && displayName != "null" -> displayName
                    else -> "卡$index"
                }
                simCards.add(name)
                index++
            }
        }
    } catch (e: Exception) {
        // 如果查询失败，返回空列表
    }
    return simCards
}