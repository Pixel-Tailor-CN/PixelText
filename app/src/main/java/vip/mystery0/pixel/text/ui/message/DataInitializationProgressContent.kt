package vip.mystery0.pixel.text.ui.message

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.DataInitializationProgress
import vip.mystery0.pixel.text.domain.model.InitializationPhase
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun DataInitializationProgressContent(
    phase: InitializationPhase,
    progress: DataInitializationProgress?,
) {
    val context = LocalContext.current
    // 阶段切换时不展示上一个阶段的处理数量和预计时间。
    val currentProgress = progress?.takeIf { it.phase == phase }
    val waiting = currentProgress?.isWaiting == true
    val step = when (phase) {
        InitializationPhase.MIRROR -> 1
        InitializationPhase.MMS_TEXT -> 2
        InitializationPhase.SPAM -> 3
        InitializationPhase.VERIFICATION, InitializationPhase.COMPLETE -> 4
    }
    val description = stringResource(
        when (phase) {
            InitializationPhase.MIRROR -> R.string.msg_init_phase_mirror
            InitializationPhase.MMS_TEXT -> R.string.msg_init_phase_mms_text
            InitializationPhase.SPAM -> R.string.msg_init_phase_spam
            InitializationPhase.VERIFICATION -> R.string.msg_init_phase_verification
            InitializationPhase.COMPLETE -> R.string.msg_init_phase_complete
        }
    )
    val total = currentProgress?.total
    val completed = currentProgress?.completed?.coerceAtLeast(0) ?: 0L
    val fraction = total
        ?.takeIf { it > 0 }
        ?.let { (completed.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.msg_init_current_step, step, description),
            style = MaterialTheme.typography.titleMedium,
        )

        if (fraction != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = stringResource(
                        R.string.msg_init_processed_of_total,
                        formatInitializationCount(completed),
                        formatInitializationCount(total),
                    ),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.msg_init_percent, (fraction * 100).toInt()),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (waiting) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (total == 0L) {
            Text(
                text = stringResource(R.string.msg_init_step_no_action),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            currentProgress?.let {
                Text(
                    text = stringResource(R.string.msg_init_processed_count, formatInitializationCount(completed)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!waiting) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        Text(
            text = when {
                phase == InitializationPhase.COMPLETE -> stringResource(R.string.msg_init_almost_done)
                waiting && phase == InitializationPhase.VERIFICATION ->
                    stringResource(R.string.msg_init_waiting_verification)
                waiting -> stringResource(R.string.msg_init_waiting)
                currentProgress == null -> stringResource(R.string.msg_init_estimating_remaining)
                total == 0L -> stringResource(R.string.msg_init_next_step)
                currentProgress.estimatedRemainingMillis != null ->
                    stringResource(
                        R.string.msg_init_elapsed_and_remaining,
                        formatInitializationDuration(context, currentProgress.elapsedMillis),
                        formatRemainingDuration(context, currentProgress.estimatedRemainingMillis),
                    )
                else -> stringResource(
                    R.string.msg_init_elapsed_estimating,
                    formatInitializationDuration(context, currentProgress.elapsedMillis),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatInitializationCount(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

private fun formatRemainingDuration(context: Context, millis: Long): String =
    if (millis < 1_000) {
        context.getString(R.string.msg_init_remaining_less_than_1s)
    } else {
        context.getString(R.string.msg_init_remaining_approx, formatInitializationDuration(context, millis))
    }

private fun formatInitializationDuration(context: Context, millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return when {
        seconds < 1 -> context.getString(R.string.msg_init_duration_less_than_1s)
        seconds < 60 -> context.getString(R.string.msg_init_duration_seconds, seconds)
        seconds % 60 == 0L -> context.getString(R.string.msg_init_duration_minutes, seconds / 60)
        else -> context.getString(R.string.msg_init_duration_minutes_seconds, seconds / 60, seconds % 60)
    }
}
