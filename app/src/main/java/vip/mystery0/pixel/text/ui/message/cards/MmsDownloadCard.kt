package vip.mystery0.pixel.text.ui.message.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.mms.MmsDownloadCoordinator

@Composable
fun MmsDownloadCard(mmsId: Long, interactionEnabled: Boolean = true, isSelected: Boolean = false) {
    val downloads = koinInject<MmsDownloadCoordinator>()
    val scope = rememberCoroutineScope()
    var phase by remember(mmsId) { mutableStateOf(downloads.state(mmsId)) }
    var error by remember(mmsId) { mutableStateOf<String?>(null) }
    var submitting by remember(mmsId) { mutableStateOf(false) }
    LaunchedEffect(mmsId) {
        while (true) {
            phase = downloads.state(mmsId)
            delay(1000)
        }
    }
    val busy = submitting || phase == "downloading" || phase == "persisting"
    Card(colors = if (isSelected) CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(error ?: when {
                phase == "persisting" -> "正在保存彩信"
                busy -> "正在下载彩信"
                phase == "save_failed" -> "彩信已下载，保存失败，可重试保存"
                phase == "parse_failed" -> "彩信已下载，暂时无法解析，原件已保留"
                phase == "failed" -> "彩信下载失败"
                phase == "expired" -> "彩信已过期，服务器副本无法下载"
                phase == "invalid_subscription" -> "无法确定接收彩信的 SIM 卡"
                phase == "sim_unavailable" -> "接收彩信的 SIM 卡不可用"
                phase == "download_uncertain" -> "下载结果未确认，已有文件已保留，可重新下载"
                phase == "retry_wait" -> "下载失败，等待自动重试"
                phase == "state_invalid" -> "下载状态异常，请重新下载"
                else -> "彩信尚未下载"
            })
            TextButton(enabled = interactionEnabled && !busy, onClick = {
                submitting = true
                error = null
                scope.launch {
                    try {
                        downloads.requestDownload(mmsId, true)
                        phase = downloads.state(mmsId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        phase = downloads.state(mmsId)
                        error = if (failure is IllegalStateException && failure.message?.any { it.code > 127 } == true)
                            failure.message else "暂时无法完成，请稍后重试"
                    } finally {
                        submitting = false
                    }
                }
            }) { Text(when (phase) { "parse_failed" -> "重试解析"; "save_failed" -> "重试保存"; "failed" -> "重试"; else -> "下载" }) }
        }
    }
}
