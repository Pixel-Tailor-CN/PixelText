package vip.mystery0.pixel.text.ui.message.mms

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
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
    onMessageClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    var originals by rememberSaveable(model.key.sourceId) { mutableStateOf(false) }
    val controller: MmsPlaybackController = koinInject()
    val click by rememberUpdatedState(onMessageClick)
    val longClick by rememberUpdatedState(onLongClick)
    val color = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = color, contentColor = textColor, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().semantics {
            selected = isSelected
            if (interactionEnabled) onLongClick("选择彩信") { longClick(); true }
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
                if (up == null && !moved && interactionEnabled) {
                    longClick()
                    do { val event = awaitPointerEvent(PointerEventPass.Initial); event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                } else if (up == true && !moved && selectionMode && interactionEnabled) click()
            }
        }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            model.subject?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (model.pendingDownload) MmsDownloadCard(model.key.sourceId)
            if (model.issues.isNotEmpty()) Text("彩信结构或资源引用不完整，已按可用附件显示；全部原件仍可查看", style = MaterialTheme.typography.bodySmall)
            if (model.preparing) Text(model.summary)
            else if (model.pages.isNotEmpty()) {
                MmsPresentation(model, interactionEnabled && !selectionMode, onOpenPart, controller)
                val unreferenced = model.parts.filter { it.key.partId in model.attachmentPartIds && it.key.partId in model.bodyPartIds }
                if (unreferenced.isNotEmpty()) Text("未引用的附件", style = MaterialTheme.typography.titleSmall)
                unreferenced.forEach { MmsContentPart(it, model.parts, isSelected, interactionEnabled && !selectionMode, onOpenPart) }
            } else {
                model.parts.filter { it.key.partId in model.bodyPartIds }.forEach {
                    MmsContentPart(it, model.parts, isSelected, interactionEnabled && !selectionMode, onOpenPart)
                }
                if (model.parts.isEmpty() && model.subject.isNullOrBlank() && !model.pendingDownload) Text("彩信暂无可读内容")
            }
            if (model.parts.isNotEmpty()) {
                TextButton(enabled = interactionEnabled && !selectionMode, onClick = { originals = !originals }) {
                    Text(if (originals) "收起原件" else "全部原件（${model.parts.size}）")
                }
                if (originals) model.parts.forEach { part ->
                    MmsFileCard(part, isSelected = isSelected, interactionEnabled = interactionEnabled && !selectionMode, onOpenPart = onOpenPart)
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
        MmsContentKind.CONTACT -> MmsContactCard(part, parts, isSelected = selected, interactionEnabled = enabled)
        MmsContentKind.CALENDAR -> MmsCalendarCard(part, isSelected = selected, interactionEnabled = enabled)
        else -> MmsFileCard(part, isSelected = selected, interactionEnabled = enabled, onOpenPart = onOpen)
    }
}
