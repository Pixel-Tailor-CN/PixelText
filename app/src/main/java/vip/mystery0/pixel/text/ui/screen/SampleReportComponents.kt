package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import vip.mystery0.pixel.text.domain.hub.SampleReportStatus
import vip.mystery0.pixel.text.domain.sample.SampleCategory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SampleReportTopBar(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
    }, actions = { action() }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
internal fun SampleReportStatusLabel(status: SampleReportStatus) {
    val icon: ImageVector = when (status) {
        SampleReportStatus.APPROVED -> Icons.Rounded.CheckCircle
        SampleReportStatus.REJECTED -> Icons.Rounded.Cancel
        SampleReportStatus.REMOVED -> Icons.Rounded.RemoveCircleOutline
        SampleReportStatus.RESTRICTED -> Icons.Rounded.Block
        SampleReportStatus.REVIEWING -> Icons.Rounded.Sync
        SampleReportStatus.MANUAL_REVIEW -> Icons.Rounded.PersonSearch
        SampleReportStatus.PENDING -> Icons.Rounded.Schedule
        SampleReportStatus.UNKNOWN -> Icons.AutoMirrored.Rounded.HelpOutline
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(status.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun SampleReportField(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun sampleReportRetrySeconds(retryAt: Long): Long {
    var now by remember(retryAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAt) {
        while (now < retryAt) { delay(1000); now = System.currentTimeMillis() }
    }
    return ((retryAt - now + 999) / 1000).coerceAtLeast(0)
}

@Composable
internal fun SampleReportError(message: String, retryAt: Long, onRetry: () -> Unit) {
    val seconds = sampleReportRetrySeconds(retryAt)
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, enabled = seconds == 0L) {
            Text(if (seconds > 0) "${seconds} 秒后可重试" else "重试")
        }
    }
}

/** 页面再次成为当前入口时主动更新；不进行后台轮询。 */
@Composable
internal fun SampleReportOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

internal fun sampleReportCategory(value: String): String = SampleCategory.fromValue(value)?.label ?: "未知分类"
internal fun sampleReportDate(value: Instant?): String = value?.atZone(ZoneId.systemDefault())
    ?.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) ?: "时间未知"
internal fun sampleReportMonth(value: Instant?): String = value?.atZone(ZoneId.systemDefault())
    ?.format(DateTimeFormatter.ofPattern("yyyy年M月")) ?: "时间未知"
