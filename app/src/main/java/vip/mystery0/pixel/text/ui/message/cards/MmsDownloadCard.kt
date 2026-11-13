package vip.mystery0.pixel.text.ui.message.cards

import kotlin.time.Duration.Companion.milliseconds

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.mms.MmsDownloadCoordinator

@Composable
fun MmsDownloadCard(mmsId: Long, interactionEnabled: Boolean = true, isSelected: Boolean = false) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val downloads = koinInject<MmsDownloadCoordinator>()
    val scope = rememberCoroutineScope()
    var phase by remember(mmsId) { mutableStateOf(downloads.state(mmsId)) }
    var error by remember(mmsId) { mutableStateOf<String?>(null) }
    var submitting by remember(mmsId) { mutableStateOf(false) }
    LaunchedEffect(mmsId) {
        while (true) {
            phase = downloads.state(mmsId)
            delay(1000.milliseconds)
        }
    }
    val busy = submitting || phase == "downloading" || phase == "persisting"
    Card(colors = if (isSelected) CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                error ?: stringResource(
                    when {
                        phase == "persisting" -> R.string.msg_mms_download_persisting
                        busy -> R.string.msg_mms_download_downloading
                        phase == "save_failed" -> R.string.msg_mms_download_save_failed
                        phase == "parse_failed" -> R.string.msg_mms_download_parse_failed
                        phase == "failed" -> R.string.msg_mms_download_failed
                        phase == "expired" -> R.string.msg_mms_download_expired
                        phase == "invalid_subscription" -> R.string.msg_mms_download_invalid_subscription
                        phase == "sim_unavailable" -> R.string.msg_mms_download_sim_unavailable
                        phase == "download_uncertain" -> R.string.msg_mms_download_uncertain
                        phase == "retry_wait" -> R.string.msg_mms_download_retry_wait
                        phase == "state_invalid" -> R.string.msg_mms_download_state_invalid
                        else -> R.string.msg_mms_download_not_downloaded
                    }
                )
            )
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
                        error = if (failure is IllegalStateException && failure.message?.any { it.code > 127 } == true) {
                            failure.message
                        } else {
                            resources.getString(R.string.msg_mms_download_error_fallback)
                        }
                    } finally {
                        submitting = false
                    }
                }
            }) {
                Text(
                    stringResource(
                        when (phase) {
                            "parse_failed" -> R.string.msg_mms_download_action_retry_parse
                            "save_failed" -> R.string.msg_mms_download_action_retry_save
                            "failed" -> R.string.msg_action_retry
                            else -> R.string.msg_mms_download_action_download
                        }
                    )
                )
            }
        }
    }
}
