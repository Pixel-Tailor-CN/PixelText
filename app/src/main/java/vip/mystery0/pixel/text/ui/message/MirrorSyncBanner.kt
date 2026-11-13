package vip.mystery0.pixel.text.ui.message

import kotlin.time.Duration.Companion.milliseconds

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.mirror.MirrorInitializationPhase
import vip.mystery0.pixel.text.domain.model.mirror.MirrorSyncState
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler

@Composable
@OptIn(ExperimentalCoroutinesApi::class)
fun MirrorSyncBanner() {
    val repository = koinInject<MessageMirrorRepository>()
    val scheduler = koinInject<MessageMirrorScheduler>()
    val notice by remember(repository) {
        repository.observeSyncState()
            .map { it.bannerNotice() }
            .distinctUntilChanged()
            .transformLatest { candidate ->
                // 尚未读到状态及快速完成的同步均不展示；恢复正常后立即隐藏。
                emit(null)
                if (candidate != null) {
                    delay(1_000.milliseconds)
                    emit(candidate)
                }
            }
    }.collectAsState(initial = null)
    val currentNotice = notice ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(currentNotice.messageRes), style = MaterialTheme.typography.bodySmall)
            if (currentNotice.retryable) {
                TextButton(onClick = { scheduler.schedule(forceReconcile = true) }) {
                    Text(stringResource(R.string.msg_action_retry))
                }
            }
        }
    }
}

private enum class MirrorBannerNotice(val messageRes: Int, val retryable: Boolean) {
    INITIAL_IMPORT(R.string.msg_mirror_initial_import, false),
    SYNC_FAILURE(R.string.msg_mirror_sync_failure, true),
    ATTACHMENT_FAILURE(R.string.msg_mirror_attachment_failure, true),
}

private fun MirrorSyncState.bannerNotice(): MirrorBannerNotice? = when {
    attachmentFailureCount > 0 -> MirrorBannerNotice.ATTACHMENT_FAILURE
    incompleteStructureCount > 0 || collections.any {
        // 预算让出和中断续扫是正常进度，不是用户需要重试的错误。
        it.error != null && it.error !in setOf("budget_yield", "interrupted_import")
    } -> MirrorBannerNotice.SYNC_FAILURE
    // 首次基础导入完成之后，普通后台对账和下拉刷新不再使用横幅显示进度。
    lastSuccessTime == null && phase != MirrorInitializationPhase.COMPLETE -> MirrorBannerNotice.INITIAL_IMPORT
    else -> null
}
