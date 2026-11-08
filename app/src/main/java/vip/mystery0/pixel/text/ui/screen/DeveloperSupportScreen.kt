package vip.mystery0.pixel.text.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.SupportChannelId
import vip.mystery0.pixel.text.ui.component.AppEmptyState
import vip.mystery0.pixel.text.util.openSupportApp
import vip.mystery0.pixel.text.viewmodel.DeveloperSupportViewModel

@Composable
fun DeveloperSupportScreen(
    onNavigateBack: () -> Unit,
    viewModel: DeveloperSupportViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var enlargedImage by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("支持开发者") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error || !state.enabled -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppEmptyState(if (state.error) "赞助信息暂不可用，请稍后重试" else "赞助入口已暂停")
                if (state.error) {
                    FilledTonalButton(onClick = viewModel::reload) { Text("重试") }
                }
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://pixel.mystery0.app/#support".toUri()))
                    } catch (_: Exception) {
                        viewModel.showMessage("无法打开官网，请检查浏览器是否可用")
                    }
                }) { Text("访问官网") }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        "自愿支持开发与维护，不解锁任何功能或权益。付款前请核对收款方。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.channels, key = { it.id.value }) { channel ->
                    val name = when (channel.id) {
                        SupportChannelId.ALIPAY -> "支付宝"
                        SupportChannelId.WECHAT -> "微信"
                    }
                    val image = state.images[channel.id]
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(name, style = MaterialTheme.typography.titleLarge)
                            when {
                                image?.bitmap != null -> Image(
                                    bitmap = image.bitmap.asImageBitmap(),
                                    contentDescription = "$name 收款码，点按放大",
                                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
                                        .height(420.dp)
                                        .background(Color.White)
                                        .clickable(onClickLabel = "放大收款码") { enlargedImage = image.bitmap },
                                    contentScale = ContentScale.Fit,
                                )
                                image?.loading == true -> CircularProgressIndicator()
                                else -> {
                                    Text("收款码加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { viewModel.retryImage(channel) }) { Text("重新加载") }
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.saveImage(channel) },
                                    enabled = image?.bytes != null && !image.saving,
                                ) { Text(if (image?.saving == true) "正在保存…" else "保存收款码") }
                                FilledTonalButton(onClick = {
                                    if (!context.openSupportApp(channel)) {
                                        viewModel.showMessage("无法打开$name，请确认已安装且可用")
                                    }
                                }) { Text("打开$name") }
                            }
                            Text(
                                "可保存收款码后，在$name 扫一扫中从相册选择。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    enlargedImage?.let { bitmap ->
        Dialog(onDismissRequest = { enlargedImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(bitmap.asImageBitmap(), "收款码", modifier = Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
                TextButton(onClick = { enlargedImage = null }) { Text("关闭") }
            }
        }
    }
}
