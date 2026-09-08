@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package vip.mystery0.pixel.text.ui.message.mms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.ContentFrame
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.data.source.mms.mediaCacheKey
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent

@Composable
fun MmsMediaViewer(part: MmsPartContent, onBack: () -> Unit, controller: MmsPlaybackController = koinInject()) {
    BackHandler(onBack = onBack)
    MmsPlaybackSession(controller)
    // 切换附件只停止，绝不自动启动新媒体。
    LaunchedEffect(part.mediaCacheKey(), part.state) { controller.onViewerSourceChanged(part) }
    Scaffold(topBar = { TopAppBar(title = { Text(if (part.kind == MmsContentKind.VIDEO) "视频" else "音频") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") } }, actions = { MmsAttachmentActions(part, menuOnly = true) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(part.displayName)
            MmsMediaContent(part, controller)
        }
    }
}

/** SMIL 可直接复用；外壳安装一次 MmsPlaybackSession，并在翻页时调用 stop。 */
@Composable
fun MmsMediaContent(part: MmsPartContent, controller: MmsPlaybackController, modifier: Modifier = Modifier, interactionEnabled: Boolean = true) {
    val playback by controller.state.collectAsState()
    val player by controller.player.collectAsState()
    val selected = playback.partKey == part.key
    val showPause = selected && playback.playRequested
    var dragPosition by remember(part.mediaCacheKey()) { mutableStateOf<Float?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (part.kind == MmsContentKind.VIDEO) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                ContentFrame(player = if (selected) player else null, modifier = Modifier.fillMaxSize())
                if (!selected) Text("点按播放视频")
            }
        }
        MmsPartStatus(part)
        if (selected && playback.isBuffering) CircularProgressIndicator()
        if (selected) playback.error?.let { Text(it) }
        val duration = if (selected) playback.durationMillis else 0
        val position = if (selected) playback.positionMillis else 0
        Slider(
            value = dragPosition ?: position.toFloat().coerceAtMost(duration.toFloat()),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = { dragPosition?.let { controller.seekTo(it.toLong()) }; dragPosition = null },
            enabled = interactionEnabled && selected && duration > 0 && player?.isCurrentMediaItemSeekable == true,
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            modifier = Modifier.semantics { contentDescription = "播放进度" },
        )
        Text("${formatMediaTime((dragPosition?.toLong() ?: position))} / ${formatMediaTime(duration)}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = interactionEnabled && part.statusInfo().actionable, onClick = {
                if (showPause) controller.pause() else controller.play(part)
            }) { Text(if (showPause) "暂停" else "播放") }
            TextButton(enabled = interactionEnabled && selected, onClick = controller::stop) { Text("停止") }
        }
    }
}
