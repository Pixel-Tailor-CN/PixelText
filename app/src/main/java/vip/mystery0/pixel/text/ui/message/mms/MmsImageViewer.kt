package vip.mystery0.pixel.text.ui.message.mms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.data.source.mms.mediaCacheKey
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent

@Composable
fun MmsImageViewer(part: MmsPartContent, onBack: () -> Unit, imageNumber: Int = 1) {
    BackHandler(onBack = onBack)
    val identity = part.mediaCacheKey()
    var scale by remember(identity) { mutableFloatStateOf(1f) }
    var offset by remember(identity) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("图片 $imageNumber") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
        }, actions = { TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("重置") }; MmsAttachmentActions(part, menuOnly = true) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(part.displayName, Modifier.padding(horizontal = 16.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { size = it }
                .pointerInput(identity) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2f
                        offset = Offset.Zero
                    })
                }
                .pointerInput(identity) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val maxX = size.width * (scale - 1f) / 2
                        val maxY = size.height * (scale - 1f) / 2
                        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    }
                }) {
                if (part.statusInfo().actionable) MmsLocalImage(
                    uri = part.localUri, cacheKey = identity, cacheEnabled = part.contentHash != null,
                    description = "图片 $imageNumber，${part.displayName}",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    },
                ) else MmsPartStatus(part)
            }
        }
    }
}
