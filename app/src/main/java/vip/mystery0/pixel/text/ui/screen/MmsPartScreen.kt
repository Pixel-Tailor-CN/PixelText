package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.mms.*
import vip.mystery0.pixel.text.viewmodel.MmsContentViewModel
import vip.mystery0.pixel.text.ui.message.mms.*

/** 路由只保存身份；返回、进程恢复和附件变化都从仓库重新订阅。 */
@Composable
fun MmsPartScreen(key: MmsPartKey, onBack: () -> Unit) {
    val viewModel = koinViewModel<MmsContentViewModel>()
    val model by viewModel.content.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    LaunchedEffect(key.message) { viewModel.load(key.message) }
    val part = model?.parts?.firstOrNull { it.key == key }
    // 媒体刷新准备态继续持有同一查看会话，避免销毁播放器时抹掉换源停止原因。
    if (part != null && (part.issue != "preparing" ||
            part.kind in setOf(MmsContentKind.AUDIO, MmsContentKind.VIDEO))) when (part.kind) {
        MmsContentKind.IMAGE -> { MmsImageViewer(part, onBack); return }
        MmsContentKind.AUDIO, MmsContentKind.VIDEO -> { MmsMediaViewer(part, onBack); return }
        MmsContentKind.HTML -> { MmsHtmlViewer(part, model!!.parts, onBack); return }
        else -> Unit
    }
    Scaffold(topBar = { TopAppBar(title = { Text("彩信附件") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (part == null) Text(if (loaded) "附件已不存在或尚未同步" else "正在读取附件")
            else when (part.kind) {
                MmsContentKind.CONTACT -> MmsContactCard(part, model!!.parts)
                MmsContentKind.CALENDAR -> MmsCalendarCard(part)
                else -> {
                    MmsFileCard(part)
                    part.text?.let { text -> SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) } }
                }
            }
        }
    }
}
