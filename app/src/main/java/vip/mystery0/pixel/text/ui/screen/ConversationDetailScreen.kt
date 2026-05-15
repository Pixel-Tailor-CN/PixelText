package vip.mystery0.pixel.text.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.MessageItem
import vip.mystery0.pixel.text.ui.message.cards.MmsImageCard
import vip.mystery0.pixel.text.ui.message.cards.OriginalTextCard
import vip.mystery0.pixel.text.ui.message.cards.SpamMessageCard
import vip.mystery0.pixel.text.ui.message.factory.MessageCardFactory
import vip.mystery0.pixel.text.util.SimInfo
import vip.mystery0.pixel.text.util.SimInfoProvider
import vip.mystery0.pixel.text.viewmodel.ConversationDetailViewModel
import vip.mystery0.pixel.text.viewmodel.ManualSpamCheckState
import vip.mystery0.pixel.text.viewmodel.MessageUiState
import vip.mystery0.pixel.text.viewmodel.SendResultEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationDetailScreen(
    threadId: Long,
    address: String,
    onNavigateBack: () -> Unit,
    isTablet: Boolean = false,
    viewModel: ConversationDetailViewModel = koinViewModel()
) {
    LaunchedEffect(threadId, address) {
        viewModel.loadThread(threadId, address)
    }

    val uiState by viewModel.uiState.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val manualSpamChecks by viewModel.manualSpamChecks.collectAsState()
    val context = LocalContext.current
    val selectedMessageIds = remember { mutableStateListOf<Long>() }
    var messageText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // 双卡场景：加载当前激活的 SIM 列表，单卡 / 无权限时为空列表
    val simList = remember { SimInfoProvider.getActiveSimList(context) }
    var selectedSubId by remember(simList) {
        val default = SimInfoProvider.getDefaultSmsSubscriptionId()
        val resolved = simList.firstOrNull { it.subscriptionId == default }?.subscriptionId
            ?: simList.firstOrNull()?.subscriptionId
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        mutableStateOf(resolved)
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
                            // TODO: Delete messages
                            selectedMessageIds.clear()
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
                                            // TODO: Forward message
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
                        IconButton(onClick = { /* TODO */ }) {
                            Icon(Icons.Rounded.Call, contentDescription = "Call")
                        }
                        IconButton(onClick = { /* TODO */ }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
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
                        TextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            placeholder = { Text("Text message") },
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            maxLines = 4
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
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
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

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    reverseLayout = true
                ) {
                    items(
                        items = state.messages,
                        key = { it.id }
                    ) { message ->
                        val isSelected = selectedMessageIds.contains(message.id)
                        MessageItem(
                            message = message,
                            isSelected = isSelected,
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
                            }
                        )
                    }
                }
            }
        }
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
    val currentLabel = simList.firstOrNull { it.subscriptionId == selectedSubId }?.displayName
        ?: simList.firstOrNull()?.displayName.orEmpty()

    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { expanded = true }
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            simList.forEach { sim ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (sim.phoneNumber.isNullOrBlank()) sim.displayName
                            else "${sim.displayName}  ${sim.phoneNumber}"
                        )
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
