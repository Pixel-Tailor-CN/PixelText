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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import vip.mystery0.pixel.text.R
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
        AlertDialog(
            onDismissRequest = { confirmEvent = null },
            title = { Text(stringResource(R.string.msg_mms_calendar_confirm_title)) },
            text = { Text(stringResource(R.string.msg_mms_calendar_confirm_body, event.importWarning.orEmpty())) },
            confirmButton = {
                TextButton(enabled = enabled, onClick = { confirmEvent = null; insert(event) }) {
                    Text(stringResource(R.string.msg_mms_calendar_confirm_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEvent = null }) { Text(stringResource(R.string.msg_action_cancel)) }
            },
        )
    }
    Card(modifier.fillMaxWidth().clickable(enabled = interactionEnabled && selectionMode, onClick = onMessageClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.msg_mms_calendar_header, part.displayName), style = MaterialTheme.typography.labelLarge)
            MmsPartStatus(part)
            part.calendarEvents.forEach { event ->
                Text(event.title ?: stringResource(R.string.msg_mms_calendar_untitled_event), style = MaterialTheme.typography.titleMedium)
                val zone = event.zoneId?.let(ZoneId::of) ?: ZoneOffset.UTC
                val formatter = DateTimeFormatter.ofPattern(if (event.allDay) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm:ss").withZone(zone)
                val startSuffix = if (event.allDay) stringResource(R.string.msg_mms_calendar_all_day) else " ${zone.id}"
                val endSuffix = if (event.allDay) stringResource(R.string.msg_mms_calendar_exclusive_end) else " ${zone.id}"
                event.start?.let { Text(stringResource(R.string.msg_mms_calendar_start, formatter.format(it), startSuffix)) }
                event.end?.let { Text(stringResource(R.string.msg_mms_calendar_end, formatter.format(it), endSuffix)) }
                event.location?.let { Text(stringResource(R.string.msg_mms_calendar_location, it)) }
                event.description?.let { Text(it) }
                event.recurrence?.let { Text(stringResource(R.string.msg_mms_calendar_recurrence, it), style = MaterialTheme.typography.bodySmall) }
                event.importWarning?.let { Text(it, color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = enabled && event.canImport, onClick = {
                    if (event.importWarning != null) confirmEvent = event else insert(event)
                }) {
                    Text(
                        stringResource(
                            if (event.recurrence == null) R.string.msg_mms_calendar_add_event
                            else R.string.msg_mms_calendar_add_occurrence,
                        )
                    )
                }
            }
            MmsAttachmentActions(part, enabled = enabled, onFeedback = onFeedback)
        }
    }
}
