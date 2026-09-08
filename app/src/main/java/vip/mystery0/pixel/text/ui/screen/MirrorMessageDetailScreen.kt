package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import android.text.format.DateFormat
import java.util.Date
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import vip.mystery0.pixel.text.util.SimInfoProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.ui.message.MirrorSyncBanner
import vip.mystery0.pixel.text.ui.message.cards.MmsDownloadCard
import vip.mystery0.pixel.text.ui.message.cards.MmsImageCard
import vip.mystery0.pixel.text.viewmodel.MirrorMessageDetailViewModel

@Composable
fun MirrorMessageDetailScreen(key: SourceMessageKey, onBack: () -> Unit,
    onOpenPart: (vip.mystery0.pixel.text.domain.model.mms.MmsPartKey) -> Unit = {}) {
    val contentViewModel = koinViewModel<vip.mystery0.pixel.text.viewmodel.MmsContentViewModel>()
    val content by contentViewModel.content.collectAsState()
    LaunchedEffect(key) { if (key.transport == vip.mystery0.pixel.text.domain.model.mirror.MessageTransport.MMS) contentViewModel.load(key) }
    vip.mystery0.pixel.text.ui.message.mms.MmsPlaybackSession(org.koin.compose.koinInject())
    val viewModel = koinViewModel<MirrorMessageDetailViewModel>()
    val context = LocalContext.current
    val message by viewModel.message.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    LaunchedEffect(key) { viewModel.load(key) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("消息详情") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            MirrorSyncBanner()
            val current = message
            if (current == null) {
                Text(if (loaded) "消息已不存在或尚未同步" else "正在读取消息", Modifier.padding(16.dp))
            } else {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val incoming = current.boxType == 1
                    val primary = current.address?.takeIf { it.isNotBlank() }
                        ?: current.addresses.firstOrNull { it.type == if (incoming) 137 else 151 }?.address
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(primary ?: "未知号码", style = MaterialTheme.typography.titleLarge)
                        Text(listOfNotNull(
                            if (incoming) "收到的消息" else "发出的消息",
                            current.timestamp?.let { DateFormat.format("yyyy-MM-dd HH:mm", Date(it)).toString() },
                            current.subscriptionId?.takeIf { it >= 0 }?.let { id ->
                                SimInfoProvider.getActiveSimList(context).firstOrNull { it.subscriptionId == id }?.let { sim ->
                                    "${if (sim.slotIndex >= 0) "SIM ${sim.slotIndex + 1}" else "SIM"} · ${sim.displayName}"
                                }
                                    ?: "SIM 信息不可用"
                            },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(137 to "发件人", 151 to "收件人", 130 to "抄送", 129 to "密送").forEach { (type, label) ->
                            val addresses = current.addresses.filter { it.type == type }.mapNotNull { it.address }
                                .filter { it.isNotBlank() && it != primary && it != "insert-address-token" }.distinct()
                            if (addresses.isNotEmpty()) Text("$label：${addresses.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (key.transport == vip.mystery0.pixel.text.domain.model.mirror.MessageTransport.MMS) {
                        content?.let { vip.mystery0.pixel.text.ui.message.mms.MmsContent(it, false, true, onOpenPart, detailMode = true) }
                            ?: Text("正在读取彩信")
                    } else current.body?.let { Text(it) }

                }
            }
        }
    }
}
