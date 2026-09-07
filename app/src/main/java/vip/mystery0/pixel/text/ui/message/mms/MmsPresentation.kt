package vip.mystery0.pixel.text.ui.message.mms

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import vip.mystery0.pixel.text.domain.model.mms.*

/** 播放会话由页面外壳持有；页内没有播放器或自动启动声音的独立生命周期。 */
@Composable
fun MmsPresentation(model: MmsContentModel, enabled: Boolean, onOpenPart: (MmsPartKey) -> Unit,
    controller: MmsPlaybackController) {
    var page by rememberSaveable(model.key.sourceId) { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    val presentationOwner by controller.presentation.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pages = model.pages
    val currentPage = page.coerceIn(pages.indices)
    fun stop() {
        playing = false
        controller.endPresentation(model.key)
        if (controller.state.value.partKey?.message == model.key) controller.stop()
    }
    DisposableEffect(lifecycle, model.key) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(presentationOwner) { if (presentationOwner != model.key) playing = false }
    LaunchedEffect(enabled, visible, model.revision) { if (!enabled || !visible) stop() }
    LaunchedEffect(playing, pages) {
        if (!playing) return@LaunchedEffect
        for (index in currentPage..pages.lastIndex) {
            if (controller.presentation.value != model.key) break
            controller.stop()
            page = index
            val media = pages[index].partIds.mapNotNull { id -> model.parts.firstOrNull { it.key.partId == id } }
                .filter { it.kind == MmsContentKind.AUDIO || it.kind == MmsContentKind.VIDEO }
            if (media.isEmpty()) delay(pages[index].durationMillis)
            else for (part in media) {
                controller.play(part)
                delay((pages[index].durationMillis / media.size).coerceAtLeast(100))
                controller.stop()
            }
        }
        stop()
    }
    Column(Modifier.onGloballyPositioned { val bounds = it.boundsInWindow(); visible = bounds.width > 0 && bounds.height > 0 },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("第 ${currentPage + 1} / ${pages.size} 页", style = MaterialTheme.typography.titleSmall)
        pages[currentPage].partIds.forEach { id ->
            model.parts.firstOrNull { it.key.partId == id }?.let { part ->
                if (part.kind == MmsContentKind.IMAGE) MmsImageContent(part, { stop(); onOpenPart(part.key) }, visible = visible, interactionEnabled = enabled)
                else if (part.kind == MmsContentKind.AUDIO || part.kind == MmsContentKind.VIDEO) {
                    Text(part.displayName)
                    MmsMediaContent(part, controller, interactionEnabled = enabled)
                } else MmsContentPart(part, model.parts, false, enabled) { stop(); onOpenPart(it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = enabled && currentPage > 0, onClick = { stop(); page = currentPage - 1 }) { Text("上一页") }
            TextButton(enabled = enabled && currentPage < pages.lastIndex, onClick = { stop(); page = currentPage + 1 }) { Text("下一页") }
        }
        Button(enabled = enabled, onClick = { if (playing) stop() else { controller.beginPresentation(model.key); playing = true } }) { Text(if (playing) "停止顺序播放" else "顺序播放") }
    }
}
