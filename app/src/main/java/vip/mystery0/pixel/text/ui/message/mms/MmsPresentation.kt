package vip.mystery0.pixel.text.ui.message.mms

import kotlin.time.Duration.Companion.milliseconds

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.Alignment
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
    controller: MmsPlaybackController, isSelected: Boolean = false,
    pages: List<MmsPresentationPage> = model.pages) {
    if (pages.isEmpty()) return
    var page by rememberSaveable(model.key.sourceId) { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<MmsPlaybackController.PresentationRequest?>(null) }
    val playing = request != null
    var visible by remember { mutableStateOf(true) }
    val presentationOwner by controller.presentation.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentPage = page.coerceIn(pages.indices)
    val presentationPartIds = pages.flatMap { it.partIds }.toSet()
    val canAutoPlay = model.parts.count {
        it.key.partId in presentationPartIds && it.kind in setOf(MmsContentKind.IMAGE, MmsContentKind.AUDIO, MmsContentKind.VIDEO)
    } > 1
    fun stop() {
        request?.let(controller::endPresentation)
        request = null
        if (controller.state.value.partKey?.message == model.key) controller.stop()
    }
    DisposableEffect(lifecycle, model.key) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(presentationOwner) { if (presentationOwner != request) request = null }
    LaunchedEffect(enabled, visible, model.revision, canAutoPlay) { if (!enabled || !visible || !canAutoPlay) stop() }
    LaunchedEffect(request, pages) {
        val activeRequest = request ?: return@LaunchedEffect
        for (index in currentPage..pages.lastIndex) {
            if (!controller.ownsPresentation(activeRequest)) return@LaunchedEffect
            controller.stopPresentationMedia(activeRequest)
            page = index
            val media = pages[index].partIds.mapNotNull { id -> model.parts.firstOrNull { it.key.partId == id } }
                .filter { it.kind == MmsContentKind.AUDIO || it.kind == MmsContentKind.VIDEO }
            if (media.isEmpty()) delay(pages[index].durationMillis.milliseconds)
            else for (part in media) {
                if (!controller.ownsPresentation(activeRequest)) return@LaunchedEffect
                controller.playPresentation(activeRequest, part)
                delay(((pages[index].durationMillis / media.size).coerceAtLeast(100)).milliseconds)
                controller.stopPresentationMedia(activeRequest)
            }
        }
        controller.endPresentation(activeRequest)
        if (request == activeRequest) request = null
    }
    Column(Modifier.onGloballyPositioned { val bounds = it.boundsInWindow(); visible = bounds.width > 0 && bounds.height > 0 },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pages[currentPage].partIds.forEach { id ->
            model.parts.firstOrNull { it.key.partId == id }?.let { part ->
                if (part.kind == MmsContentKind.IMAGE) MmsImageContent(part, { stop(); onOpenPart(part.key) }, visible = visible, interactionEnabled = enabled)
                else if (part.kind == MmsContentKind.AUDIO || part.kind == MmsContentKind.VIDEO) {
                    Text(part.displayName)
                    MmsMediaContent(part, controller, interactionEnabled = enabled)
                } else MmsContentPart(part, model.parts, isSelected, enabled) { stop(); onOpenPart(it) }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = enabled && currentPage > 0, onClick = { stop(); page = currentPage - 1 }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "上一页")
            }
            Text("${currentPage + 1} / ${pages.size}", style = MaterialTheme.typography.labelLarge)
            IconButton(enabled = enabled && currentPage < pages.lastIndex, onClick = { stop(); page = currentPage + 1 }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "下一页")
            }
            }
            if (canAutoPlay) TextButton(enabled = enabled, onClick = {
                if (playing) stop() else request = controller.beginPresentation(model.key)
            }) { Text(if (playing) "停止播放" else "自动播放") }
        }
    }
}
