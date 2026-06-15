package vip.mystery0.pixel.text.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.telephony.SubscriptionManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.ComposeSmsActivity
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.ui.message.MessageItem
import vip.mystery0.pixel.text.util.SimInfo
import vip.mystery0.pixel.text.util.SimInfoProvider
import vip.mystery0.pixel.text.viewmodel.ConversationDetailViewModel
import vip.mystery0.pixel.text.viewmodel.DeleteMessageResultEvent
import vip.mystery0.pixel.text.viewmodel.ManualSpamCheckState
import vip.mystery0.pixel.text.viewmodel.MarkSpamResultEvent
import vip.mystery0.pixel.text.viewmodel.MessageUiState
import vip.mystery0.pixel.text.viewmodel.SendResultEvent

@Composable
fun ConversationDetailScreen(
    threadId: Long,
    address: String,
    onNavigateBack: () -> Unit,
    onNavigateToSampleSubmission: (content: String, sender: String) -> Unit = { _, _ -> },
    isTablet: Boolean = false,
    initialMessageText: String = "",
    viewModel: ConversationDetailViewModel = koinViewModel(),
    settingsRepository: AppSettingsRepository = koinInject(),
) {
    LaunchedEffect(threadId, address) {
        viewModel.loadThread(threadId, address)
    }

    val uiState by viewModel.uiState.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val manualSpamChecks by viewModel.manualSpamChecks.collectAsState()
    val context = LocalContext.current
    val selectedMessageIds = remember { mutableStateListOf<Long>() }
    var deleteCandidateMessageIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var messageText by remember(threadId, address, initialMessageText) {
        mutableStateOf(initialMessageText)
    }
    var textScale by remember {
        mutableFloatStateOf(settingsRepository.getConversationDetailTextScale())
    }
    var isZoomGestureActive by remember { mutableStateOf(false) }
    val zoomGestureModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zoomActive = false
            var gestureScale = textScale
            do {
                val event = awaitPointerEvent()
                val multiTouch = event.changes.count { it.pressed } > 1
                if (multiTouch) {
                    isZoomGestureActive = true
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        val updatedScale = (gestureScale * zoom)
                            .coerceIn(
                                MIN_CONVERSATION_DETAIL_TEXT_SCALE,
                                MAX_CONVERSATION_DETAIL_TEXT_SCALE
                            )
                        if (updatedScale != gestureScale) {
                            gestureScale = updatedScale
                            textScale = updatedScale
                            settingsRepository.setConversationDetailTextScale(updatedScale)
                        }
                        zoomActive = true
                    }
                    if (zoomActive) {
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            isZoomGestureActive = false
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val selectedMessage = if (selectedMessageIds.size == 1) {
        (uiState as? MessageUiState.Success)
            ?.messages
            ?.firstOrNull { it.id == selectedMessageIds.first() }
    } else {
        null
    }
    val selectedSpamCheckState = selectedMessage?.let { manualSpamChecks[it.id] }
    val canCheckSelectedSpam =
        selectedMessage != null &&
            selectedMessage.content.isNotBlank() &&
            selectedMessage.spamScore < 0f &&
            selectedSpamCheckState !is ManualSpamCheckState.Checking
    val selectedMessageIsSpam = selectedMessage?.spamScore?.let { it >= SPAM_THRESHOLD } == true
    val spamMarkMenuText =
        if (selectedMessageIsSpam) "标记为非骚扰短信" else "标记为骚扰短信"

    // 双卡场景：加载当前激活的 SIM 列表，单卡 / 无权限时为空列表
    val simList = remember { SimInfoProvider.getActiveSimList(context) }
    var selectedSubId by remember(simList) {
        val default = SimInfoProvider.getDefaultSmsSubscriptionId()
        val resolved = simList.firstOrNull { it.subscriptionId == default }?.subscriptionId
            ?: simList.firstOrNull()?.subscriptionId
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        mutableIntStateOf(resolved)
    }

    // 监听发送结果事件，统一通过 Snackbar 提示
    LaunchedEffect(Unit) {
        viewModel.sendResultEvents.collect { event ->
            when (event) {
                is SendResultEvent.Success -> snackbarHostState.showSnackbar("已发送")
                is SendResultEvent.Failure -> snackbarHostState.showSnackbar(event.reason)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.deleteMessageResultEvents.collect { event ->
            when (event) {
                is DeleteMessageResultEvent.Success -> {
                    val message = if (event.count > 0) {
                        "已删除 ${event.count} 条消息"
                    } else {
                        "未删除任何消息"
                    }
                    snackbarHostState.showSnackbar(message)
                }

                is DeleteMessageResultEvent.Failure -> {
                    snackbarHostState.showSnackbar(event.reason)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.markSpamResultEvents.collect { event ->
            when (event) {
                is MarkSpamResultEvent.Success -> {
                    val message = if (event.markedAsSpam) {
                        "已标记为骚扰短信"
                    } else {
                        "已标记为非骚扰短信"
                    }
                    snackbarHostState.showSnackbar(message)
                }

                is MarkSpamResultEvent.Failure -> {
                    snackbarHostState.showSnackbar(event.reason)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            if (selectedMessageIds.isNotEmpty()) {
                TopAppBar(
                    title = { Text(selectedMessageIds.size.toString()) },
                    navigationIcon = {
                        IconButton(onClick = { selectedMessageIds.clear() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val clipboardManager =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            if (uiState is MessageUiState.Success) {
                                val state = uiState as MessageUiState.Success
                                val texts = state.messages.filter { it.id in selectedMessageIds }
                                    .map { it.content }
                                clipboardManager.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Messages",
                                        texts.joinToString("\n")
                                    )
                                )
                            }
                            selectedMessageIds.clear()
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = {
                            deleteCandidateMessageIds = selectedMessageIds.toSet()
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                        }
                        if (selectedMessageIds.size == 1) {
                            var showMoreMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("识别骚扰内容") },
                                        enabled = canCheckSelectedSpam,
                                        onClick = {
                                            showMoreMenu = false
                                            selectedMessage?.let { viewModel.checkSpamOnce(it) }
                                            selectedMessageIds.clear()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(spamMarkMenuText) },
                                        enabled = selectedMessage != null,
                                        onClick = {
                                            showMoreMenu = false
                                            selectedMessage?.let {
                                                viewModel.markMessageSpam(
                                                    message = it,
                                                    markedAsSpam = !selectedMessageIsSpam
                                                )
                                            }
                                            selectedMessageIds.clear()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("上报样本") },
                                        enabled = selectedMessage?.content?.isNotBlank() == true,
                                        onClick = {
                                            showMoreMenu = false
                                            selectedMessage?.let { message ->
                                                onNavigateToSampleSubmission(
                                                    message.content,
                                                    message.sender.ifBlank { address }
                                                )
                                            }
                                            selectedMessageIds.clear()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("分享") },
                                        onClick = {
                                            showMoreMenu = false
                                            if (uiState is MessageUiState.Success) {
                                                val state = uiState as MessageUiState.Success
                                                val text =
                                                    state.messages.find { it.id == selectedMessageIds.first() }?.content
                                                        ?: ""
                                                val sendIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, text)
                                                    type = "text/plain"
                                                }
                                                context.startActivity(
                                                    Intent.createChooser(
                                                        sendIntent,
                                                        null
                                                    )
                                                )
                                            }
                                            selectedMessageIds.clear()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("转发") },
                                        onClick = {
                                            showMoreMenu = false
                                            selectedMessage?.let { message ->
                                                val forwardIntent = Intent(
                                                    context,
                                                    ComposeSmsActivity::class.java
                                                ).apply {
                                                    action = Intent.ACTION_SENDTO
                                                    data = "smsto:".toUri()
                                                    putExtra("sms_body", message.content)
                                                    putExtra(Intent.EXTRA_TEXT, message.content)
                                                }
                                                context.startActivity(forwardIntent)
                                            }
                                            selectedMessageIds.clear()
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(address) },
                    navigationIcon = {
                        if (!isTablet) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                                    data = "tel:${Uri.encode(address)}".toUri()
                                }
                                runCatching {
                                    context.startActivity(dialIntent)
                                }.onFailure {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("无法打开拨号器")
                                    }
                                }
                            },
                            enabled = address.isNotBlank()
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = "Call")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    )
                )
            }
        },
        bottomBar = {
            Column {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 双卡时显示 SIM 切换；单卡 / 无权限时不展示，保持原有布局
                        if (simList.size >= 2) {
                            SimSelectorButton(
                                simList = simList,
                                selectedSubId = selectedSubId,
                                onSelected = { selectedSubId = it },
                            )
                        }
                        BasicTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 4,
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (messageText.isEmpty()) {
                                        Text(
                                            text = "请输入",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        IconButton(
                            onClick = {
                                if (messageText.isNotBlank()) {
                                    viewModel.sendMessage(
                                        address,
                                        messageText.trim(),
                                        selectedSubId
                                    )
                                    messageText = ""
                                }
                            },
                            enabled = messageText.isNotBlank() && !sending
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send",
                                tint = if (messageText.isNotBlank() && !sending)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(
                    modifier = Modifier.windowInsetsBottomHeight(
                        WindowInsets.navigationBars.union(WindowInsets.ime)
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        when (val state = uiState) {
            is MessageUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }

            is MessageUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("读取失败: ${state.message}", color = MaterialTheme.colorScheme.error)
                }
            }

            is MessageUiState.Success -> {
                val listState = rememberLazyListState()

                LaunchedEffect(listState) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                        .collect { lastIndex ->
                            if (lastIndex != null && lastIndex >= state.messages.size - 5) {
                                viewModel.loadMore()
                            }
                        }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .then(zoomGestureModifier)
                ) {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = !isZoomGestureActive,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        reverseLayout = true
                    ) {
                        items(
                            items = state.messages,
                            key = { it.stableKey }
                        ) { message ->
                            val isSelected = selectedMessageIds.contains(message.id)
                            MessageItem(
                                message = message,
                                isSelected = isSelected,
                                textScale = textScale,
                                manualSpamCheckState = manualSpamChecks[message.id],
                                onCheckSpam = { viewModel.checkSpamOnce(message) },
                                onClick = {
                                    if (selectedMessageIds.isNotEmpty()) {
                                        if (isSelected) {
                                            selectedMessageIds.remove(message.id)
                                        } else {
                                            selectedMessageIds.add(message.id)
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (selectedMessageIds.isEmpty()) {
                                        // 首次长按，进入多选模式
                                        selectedMessageIds.add(message.id)
                                    } else {
                                        // 已在多选模式，长按也切换选中状态
                                        if (isSelected) {
                                            selectedMessageIds.remove(message.id)
                                        } else {
                                            selectedMessageIds.add(message.id)
                                        }
                                    }
                                },
                                interactionEnabled = !isZoomGestureActive,
                            )
                        }
                    }
                }
            }
        }
    }

    if (deleteCandidateMessageIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteCandidateMessageIds = emptySet() },
            title = { Text("删除消息？") },
            text = { Text("将删除所选 ${deleteCandidateMessageIds.size} 条短信或彩信。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = deleteCandidateMessageIds
                        deleteCandidateMessageIds = emptySet()
                        selectedMessageIds.clear()
                        viewModel.deleteMessages(selected)
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidateMessageIds = emptySet() }) {
                    Text("取消")
                }
            }
        )
    }
}


/**
 * 输入栏中的 SIM 卡选择按钮：点击弹出菜单切换发送使用的 SIM。
 *
 * 仅在双卡（含以上）时由调用方决定是否显示。
 */
@Composable
private fun SimSelectorButton(
    simList: List<SimInfo>,
    selectedSubId: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var lastDismissedAtMillis by remember { mutableStateOf(0L) }
    val currentLabel = simList.firstOrNull { it.subscriptionId == selectedSubId }?.displayName
        ?: simList.firstOrNull()?.displayName.orEmpty()
    val popupGapPx = with(LocalDensity.current) { 32.dp.roundToPx() }

    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable {
                    val now = SystemClock.uptimeMillis()
                    if (expanded) {
                        expanded = false
                        lastDismissedAtMillis = now
                    } else if (now - lastDismissedAtMillis > SIM_MENU_REOPEN_SUPPRESS_MILLIS) {
                        expanded = true
                    }
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(
                        id = R.drawable.ic_sim
                    ),
                    contentDescription = "Select SIM",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Popup(
                popupPositionProvider = remember(popupGapPx) {
                    AboveAnchorPositionProvider(verticalGapPx = popupGapPx)
                },
                onDismissRequest = {
                    expanded = false
                    lastDismissedAtMillis = SystemClock.uptimeMillis()
                },
                properties = PopupProperties(focusable = false)
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 168.dp)
                        .padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 6.dp
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        simList.forEach { sim ->
                            DropdownMenuItem(
                                text = {
                                    Text(text = sim.displayName)
                                },
                                trailingIcon = {
                                    if (sim.subscriptionId == selectedSubId) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    onSelected(sim.subscriptionId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SPAM_THRESHOLD = 0.7f
private const val SIM_MENU_REOPEN_SUPPRESS_MILLIS = 250L
private const val MIN_CONVERSATION_DETAIL_TEXT_SCALE = 0.85f
private const val MAX_CONVERSATION_DETAIL_TEXT_SCALE = 1.8f

private class AboveAnchorPositionProvider(
    private val verticalGapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.left
            LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width
        }.coerceIn(0, windowSize.width - popupContentSize.width)
        val y = (anchorBounds.top - popupContentSize.height - verticalGapPx)
            .coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
