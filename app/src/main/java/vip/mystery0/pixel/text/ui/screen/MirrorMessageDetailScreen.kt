package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
fun MirrorMessageDetailScreen(key: SourceMessageKey, onBack: () -> Unit) {
    val viewModel = koinViewModel<MirrorMessageDetailViewModel>()
    val message by viewModel.message.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    LaunchedEffect(key) { viewModel.load(key) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("消息详情") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("返回") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            MirrorSyncBanner()
            val current = message
            if (current == null) {
                Text(if (loaded) "消息已不存在或尚未同步" else "正在读取消息", Modifier.padding(16.dp))
            } else {
                Column(Modifier.padding(16.dp)) {
                    Text(current.address.orEmpty())
                    current.addresses.forEach { Text(it.address.orEmpty()) }
                    current.decodedSubject?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    current.body?.let { Text(it) }
                    if (current.pduType == 130) MmsDownloadCard(current.key.sourceId)
                    current.parts.forEach { part ->
                        part.text?.let { Text(it) }
                        if (part.mimeType?.startsWith("image/") == true) {
                            part.attachment?.localUri?.let { MmsImageCard(listOf(it)) }
                        } else if (part.attachment != null) {
                            Text(part.filename ?: part.name ?: part.mimeType ?: "附件")
                        }
                    }
                }
            }
        }
    }
}
