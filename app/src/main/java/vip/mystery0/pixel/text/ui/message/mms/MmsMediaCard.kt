package vip.mystery0.pixel.text.ui.message.mms

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.data.source.mms.MmsMediaMetadataReader
import vip.mystery0.pixel.text.data.source.mms.MmsMediaPreview
import vip.mystery0.pixel.text.data.source.mms.mediaCacheKey
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPartKey

/** 列表仅请求元数据和一张有界缩略图，不创建播放器。 */
@Composable
fun MmsMediaCard(
    part: MmsPartContent,
    onOpenPart: (MmsPartKey) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    interactionEnabled: Boolean = true,
    onMessageClick: () -> Unit = {},
    reader: MmsMediaMetadataReader = koinInject(),
) {
    val actionable = part.statusInfo().actionable
    val preview by produceState<MmsMediaPreview?>(null, part.mediaCacheKey(), actionable) {
        value = null
        if (actionable) value = reader.read(part)
    }
    Surface(modifier.widthIn(max = 360.dp).clickable(enabled = interactionEnabled && (selectionMode || actionable)) {
        if (selectionMode) onMessageClick() else onOpenPart(part.key)
    }, shape = MaterialTheme.shapes.large,
        color = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            preview?.thumbnail?.let { Image(it.asImageBitmap(), "视频预览，${part.displayName}",
                Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Fit) }
            Text(if (part.kind == MmsContentKind.VIDEO) "视频 · ${part.displayName}" else "音频 · ${part.displayName}")
            MmsPartStatus(part)
            if (actionable) Text(preview?.error ?: preview?.durationMillis?.let(::formatMediaTime)
                ?: if (preview == null) "正在读取媒体预览" else "预览信息不可用，可尝试播放")
            Text("点按查看并播放", style = MaterialTheme.typography.labelMedium)
            MmsAttachmentActions(part, enabled = interactionEnabled && !selectionMode)
        }
    }
}

internal fun formatMediaTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
