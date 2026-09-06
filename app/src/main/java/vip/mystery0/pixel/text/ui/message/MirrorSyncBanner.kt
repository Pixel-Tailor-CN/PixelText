package vip.mystery0.pixel.text.ui.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.domain.model.mirror.MirrorInitializationPhase
import vip.mystery0.pixel.text.domain.model.mirror.MirrorSyncState
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler

@Composable
fun MirrorSyncBanner() {
    val repository = koinInject<MessageMirrorRepository>()
    val scheduler = koinInject<MessageMirrorScheduler>()
    val state by remember(repository) { repository.observeSyncState() }.collectAsState(initial = MirrorSyncState())
    val message = when {
        state.attachmentFailureCount > 0 -> "部分附件尚未复制，可稍后重试"
        state.phase == MirrorInitializationPhase.PARTIAL -> "部分消息尚未同步，结果可能不完整"
        state.phase != MirrorInitializationPhase.COMPLETE -> "正在同步消息，结果可能尚未完整"
        state.pendingAttachmentCount > 0 -> "正在复制彩信附件，消息正文已可查看"
        else -> null
    } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            if (state.phase == MirrorInitializationPhase.PARTIAL || state.attachmentFailureCount > 0) {
                TextButton(onClick = { scheduler.schedule(forceReconcile = true) }) { Text("重试") }
            }
        }
    }
}
