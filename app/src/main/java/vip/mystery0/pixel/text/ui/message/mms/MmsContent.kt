package vip.mystery0.pixel.text.ui.message.mms

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.domain.model.mms.*
import vip.mystery0.pixel.text.ui.message.cards.MmsDownloadCard

/** 会话和独立详情共用正文选择；控制文件和 alternative 未选项仅进入原件区。 */
@Composable
fun MmsContent(
    model: MmsContentModel,
    isSelected: Boolean,
    interactionEnabled: Boolean,
    onOpenPart: (MmsPartKey) -> Unit,
    selectionMode: Boolean = false,
    onMessageClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    detailMode: Boolean = false,
) {
    val linkGesture = remember { MmsLinkGestureState() }
    val displayContent = remember(model) { model.toDisplayContent() }
    val controller: MmsPlaybackController = koinInject()
    val click by rememberUpdatedState(onMessageClick)
    val longClick by rememberUpdatedState(onLongClick)
    val color = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = if (detailMode) MaterialTheme.colorScheme.surface else color,
        contentColor = if (detailMode) MaterialTheme.colorScheme.onSurface else textColor,
        shape = if (detailMode) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().semantics {
            selected = isSelected
            if (interactionEnabled && longClick != null) onLongClick("选择彩信") { longClick?.invoke(); true }
            if (interactionEnabled && selectionMode && click != null) onClick("切换选择") { click?.invoke(); true }
        }.pointerInput(selectionMode, interactionEnabled) {
            // 初始阶段观察长按，达到阈值才消费；普通附件点击仍只交给一个子控件。
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var moved = false
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (selectionMode || !interactionEnabled) event.changes.forEach { it.consume() }
                        released = !change.pressed
                    }
                    true
                }
                if (up == null && !moved && !linkGesture.active && interactionEnabled && longClick != null) {
                    longClick?.invoke()
                    do { val event = awaitPointerEvent(PointerEventPass.Initial); event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                } else if (up == true && !moved && selectionMode && interactionEnabled) click?.invoke()
            }
        }) {
        // 选中态的控件默认色也需要反色，尤其是禁用按钮和分页箭头。
        val scheme = MaterialTheme.colorScheme
        MaterialTheme(colorScheme = if (isSelected) scheme.copy(
            primary = scheme.inverseOnSurface,
            onPrimary = scheme.inverseSurface,
            onSurface = scheme.inverseOnSurface,
            onSurfaceVariant = scheme.inverseOnSurface,
        ) else scheme) {
        CompositionLocalProvider(LocalMmsLinkGesture provides linkGesture) {
        Column(Modifier.padding(if (detailMode) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.pendingDownload) MmsDownloadCard(model.key.sourceId, interactionEnabled = interactionEnabled && !selectionMode, isSelected = isSelected)
            if (model.issues.isNotEmpty()) Text("彩信结构或资源引用不完整，已按可用附件显示；可在详情信息中查看附件", style = MaterialTheme.typography.bodySmall)
            if (!model.preparing && displayContent.attachmentPages.isNotEmpty()) {
                MmsPresentation(model, interactionEnabled && !selectionMode, onOpenPart, controller,
                    isSelected = isSelected, pages = displayContent.attachmentPages)
            }
            model.subject?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (model.preparing) Text(model.summary)
            else {
                displayContent.bodyParts.forEach {
                    MmsContentPart(it, model.parts, isSelected, interactionEnabled && !selectionMode, onOpenPart)
                }
                if (model.parts.isEmpty() && model.subject.isNullOrBlank() && !model.pendingDownload) Text("彩信暂无可读内容")
            }
            if (detailMode && model.parts.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("附件（${model.parts.size}）", style = MaterialTheme.typography.titleSmall)
                model.parts.forEach { part ->
                    MmsFileCard(part, isSelected = isSelected, interactionEnabled = interactionEnabled && !selectionMode, onOpenPart = onOpenPart)
                }
            }
        }
    }
    }
    }
}

@Composable
internal fun MmsContentPart(part: MmsPartContent, parts: List<MmsPartContent>, selected: Boolean,
    enabled: Boolean, onOpen: (MmsPartKey) -> Unit) {
    when (part.kind) {
        MmsContentKind.TEXT -> {
            if (part.text != null) Text(part.text) else MmsFileCard(part, isSelected = selected, interactionEnabled = enabled, onOpenPart = onOpen)
        }
        MmsContentKind.IMAGE -> MmsImageContent(part, onOpen = { onOpen(part.key) }, interactionEnabled = enabled)
        MmsContentKind.AUDIO, MmsContentKind.VIDEO -> MmsMediaCard(part, onOpen, isSelected = selected, interactionEnabled = enabled)
        MmsContentKind.HTML -> MmsHtmlCard(part, isSelected = selected, interactionEnabled = enabled, onOpenPart = onOpen)
        MmsContentKind.CONTACT -> MmsContactCard(part, parts = parts, isSelected = selected, interactionEnabled = enabled)
        MmsContentKind.CALENDAR -> MmsCalendarCard(part, isSelected = selected, interactionEnabled = enabled)
        else -> MmsFileCard(part, isSelected = selected, interactionEnabled = enabled, onOpenPart = onOpen)
    }
}
