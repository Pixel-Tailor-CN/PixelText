package vip.mystery0.pixel.text.ui.message

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddCircleOutline
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.OriginalTextCard
import vip.mystery0.pixel.text.ui.message.factory.MessageCardFactory

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
    val context = LocalContext.current
    val selectedMessageIds = remember { mutableStateListOf<Long>() }
    var messageText by remember { mutableStateOf("") }

    Scaffold(
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
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            if (uiState is MessageUiState.Success) {
                                val state = uiState as MessageUiState.Success
                                val texts = state.messages.filter { it.id in selectedMessageIds }.map { it.content }
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("Messages", texts.joinToString("\n")))
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
                                                val text = state.messages.find { it.id == selectedMessageIds.first() }?.content ?: ""
                                                val sendIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, text)
                                                    type = "text/plain"
                                                }
                                                context.startActivity(Intent.createChooser(sendIntent, null))
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
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { /* TODO: Attachments */ }) {
                        Icon(
                            Icons.Rounded.AddCircleOutline,
                            contentDescription = "Add attachment",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.TextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Text message") },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        ),
                        maxLines = 4
                    )
                    IconButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                viewModel.sendMessage(address, messageText.trim())
                                messageText = ""
                            }
                        },
                        enabled = messageText.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send",
                            tint = if (messageText.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                    items(state.messages) { message ->
                        val isSelected = selectedMessageIds.contains(message.id)
                        MessageItem(
                            message = message,
                            isSelected = isSelected,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: MessageModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var showOriginal by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val arrangement = if (message.isReceived) Arrangement.Start else Arrangement.End
    val cardAlignment = if (message.isReceived) Alignment.Start else Alignment.End

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                indication = null,
                interactionSource = interactionSource
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = cardAlignment
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(0.85f),
            contentAlignment = if (message.isReceived) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            Column(horizontalAlignment = cardAlignment) {
                if (showOriginal || message.parsedResult is ParsedResult.None) {
                    OriginalTextCard(content = message.content, isSelected = isSelected)
                } else {
                    MessageCardFactory.CreateCard(
                        content = message.content,
                        parsedResult = message.parsedResult,
                        isSelected = isSelected
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = arrangement
        ) {
            Text(
                text = formatTimeAgo(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = vip.mystery0.pixel.text.R.drawable.ic_sim),
                        contentDescription = "SIM",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = message.simName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (message.parsedResult !is ParsedResult.None) {
                Text(
                    text = if (showOriginal) "显示智能卡片" else "显示原文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showOriginal = !showOriginal }
                )
            }
        }
    }
}

fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val oneMinute = 1000L * 60
    val oneHour = oneMinute * 60
    val oneDay = oneHour * 24
    val sevenDays = oneDay * 7

    return when {
        diff < oneMinute * 5 -> "刚刚"
        diff < oneHour -> "${diff / oneMinute}分钟前"
        diff < oneDay -> "${diff / oneHour}小时前"
        diff < sevenDays -> "${diff / oneDay}天前"
        else -> {
            val formatter =
                java.text.SimpleDateFormat("yyyy年M月d日 HH:mm:ss", java.util.Locale.getDefault())
            formatter.format(java.util.Date(timestamp))
        }
    }
}
