package vip.mystery0.pixel.text.ui.message.mms

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import vip.mystery0.pixel.text.domain.model.mms.MmsCalendarModel
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent

@Composable
fun MmsCalendarCard(
    part: MmsPartContent,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    interactionEnabled: Boolean = true,
    onMessageClick: () -> Unit = {},
    onFeedback: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val enabled = interactionEnabled && !selectionMode
    val feedback: (String) -> Unit = { onFeedback?.invoke(it) ?: Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    var confirmEvent by remember(part.key, part.contentHash, part.calendarEvents) { mutableStateOf<MmsCalendarModel?>(null) }
    fun insert(event: MmsCalendarModel) = launchMmsStructuredIntent(context, feedback) { calendarInsertIntent(event) }
    confirmEvent?.let { event ->
        AlertDialog(onDismissRequest = { confirmEvent = null }, title = { Text("核对日历导入") },
            text = { Text("${event.importWarning}\n\n仅把卡片中的本次事件交给系统编辑，保存前请核对日期、时区与字段。") },
            confirmButton = { TextButton(enabled = enabled, onClick = { confirmEvent = null; insert(event) }) { Text("继续添加本次") } },
            dismissButton = { TextButton(onClick = { confirmEvent = null }) { Text("取消") } })
    }
    Card(modifier.fillMaxWidth().clickable(enabled = interactionEnabled && selectionMode, onClick = onMessageClick),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("日历事件 · ${part.displayName}", style = MaterialTheme.typography.labelLarge)
            MmsPartStatus(part)
            part.calendarEvents.forEach { event ->
                Text(event.title ?: "未命名事件", style = MaterialTheme.typography.titleMedium)
                val zone = event.zoneId?.let(ZoneId::of) ?: ZoneOffset.UTC
                val formatter = DateTimeFormatter.ofPattern(if (event.allDay) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm:ss").withZone(zone)
                event.start?.let { Text("开始：${formatter.format(it)}${if (event.allDay) "（全天）" else " ${zone.id}"}") }
                event.end?.let { Text("结束：${formatter.format(it)}${if (event.allDay) "（不含此日）" else " ${zone.id}"}") }
                event.location?.let { Text("地点：$it") }
                event.description?.let { Text(it) }
                event.recurrence?.let { Text("重复规则：$it", style = MaterialTheme.typography.bodySmall) }
                event.importWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = enabled && event.canImport, onClick = {
                    if (event.importWarning != null) confirmEvent = event else insert(event)
                }) { Text(if (event.recurrence == null) "添加日历事件" else "添加本次事件") }
            }
            MmsAttachmentActions(part, enabled = enabled, onFeedback = onFeedback)
        }
    }
}
