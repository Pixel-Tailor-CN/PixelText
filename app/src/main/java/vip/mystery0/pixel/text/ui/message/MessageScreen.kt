package vip.mystery0.pixel.text.ui.message

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.OriginalTextCard
import vip.mystery0.pixel.text.ui.message.factory.MessageCardFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(viewModel: MessageViewModel = koinViewModel()) {
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
            if (isGranted) {
                viewModel.loadMessages()
            }
        }
    )

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadMessages()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("原点短信") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (!hasPermission) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("需要读取短信权限", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        when (val state = uiState) {
            is MessageUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is MessageUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("读取失败: ${state.message}", color = MaterialTheme.colorScheme.error)
                }
            }

            is MessageUiState.Success -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    items(state.messages) { message ->
                        MessageItem(message)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageItem(message: MessageModel) {
    var showOriginal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (showOriginal || message.parsedResult is ParsedResult.None) {
            OriginalTextCard(content = message.content)
        } else {
            MessageCardFactory.CreateCard(
                content = message.content,
                parsedResult = message.parsedResult
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
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
    return when {
        diff < 1000 * 60 -> "刚刚"
        diff < 1000 * 60 * 60 -> "${diff / (1000 * 60)}分钟前"
        diff < 1000 * 60 * 60 * 24 -> "${diff / (1000 * 60 * 60)}小时前"
        else -> "${diff / (1000 * 60 * 60 * 24)}天前"
    }
}
