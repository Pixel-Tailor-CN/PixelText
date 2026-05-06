package vip.mystery0.pixel.text.ui.message

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.ConversationModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel = koinViewModel(),
    onNavigateToDetail: (Long, String) -> Unit,
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

    val uiState by viewModel.uiState.collectAsState()

    val sheetState = rememberModalBottomSheetState()
    var showProfileSheet by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            if (hasPermission) {
                ExtendedFloatingActionButton(
                    text = { Text("Start chat") },
                    icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null) },
                    onClick = { /* TODO */ },
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

        Column(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "原点短信",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
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
                }
            }

            when (val state = uiState) {
                is ConversationListUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is ConversationListUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("读取失败: ${state.message}", color = MaterialTheme.colorScheme.error)
                    }
                }

                is ConversationListUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(state.conversations) { conversation ->
                            ConversationItem(
                                conversation = conversation,
                                onClick = {
                                    onNavigateToDetail(conversation.threadId, conversation.address)
                                }
                            )
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Color(0xFF000000.toInt() or (conversation.address.hashCode() and 0x00FFFFFF)).copy(
                        alpha = 1f
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = conversation.address.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = conversation.address,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal),
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
            Text(
                text = conversation.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = if (conversation.unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    val diff = System.currentTimeMillis() - timestamp
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = timestamp
    return when {
        diff < 1000 * 60 * 60 * 24 -> String.format(
            "%02d:%02d",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE)
        )

        diff < 1000 * 60 * 60 * 24 * 7 -> listOf(
            "周日",
            "周一",
            "周二",
            "周三",
            "周四",
            "周五",
            "周六"
        )[calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1]

        else -> String.format(
            "%02d月%02d日",
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }
}
