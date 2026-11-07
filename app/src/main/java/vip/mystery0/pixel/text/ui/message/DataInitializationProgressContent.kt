package vip.mystery0.pixel.text.ui.message

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
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.model.DataInitializationProgress
import vip.mystery0.pixel.text.domain.model.InitializationPhase
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun DataInitializationProgressContent(
    phase: InitializationPhase,
    progress: DataInitializationProgress?,
) {
    // 阶段切换时不展示上一个阶段的处理数量和预计时间。
    val currentProgress = progress?.takeIf { it.phase == phase }
    val waiting = currentProgress?.isWaiting == true
    val step = when (phase) {
        InitializationPhase.MIRROR -> 1
        InitializationPhase.MMS_TEXT -> 2
        InitializationPhase.SPAM -> 3
        InitializationPhase.VERIFICATION, InitializationPhase.COMPLETE -> 4
    }
    val description = when (phase) {
        InitializationPhase.MIRROR -> "导入短信和彩信"
        InitializationPhase.MMS_TEXT -> "解析彩信文本"
        InitializationPhase.SPAM -> "识别历史骚扰短信"
        InitializationPhase.VERIFICATION -> "建立验证码索引"
        InitializationPhase.COMPLETE -> "完成准备"
    }
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
            text = "当前步骤 $step/4 · $description",
            style = MaterialTheme.typography.titleMedium,
        )

        if (fraction != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "已处理 ${formatInitializationCount(completed)} / ${formatInitializationCount(requireNotNull(total))} 条",
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${(fraction * 100).toInt()}%",
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
                text = "当前步骤无需处理",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            currentProgress?.let {
                Text(
                    text = "已处理 ${formatInitializationCount(completed)} 条",
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
                phase == InitializationPhase.COMPLETE -> "即将完成准备，请稍候…"
                waiting && phase == InitializationPhase.VERIFICATION ->
                    "等待继续，已完成进度会保留；验证码索引将重新扫描"
                waiting -> "等待继续，已完成进度会保留"
                currentProgress == null -> "正在估算剩余时间"
                total == 0L -> "即将进入下一步骤…"
                currentProgress.estimatedRemainingMillis != null ->
                    "已耗时 ${formatInitializationDuration(currentProgress.elapsedMillis)} · " +
                        formatRemainingDuration(currentProgress.estimatedRemainingMillis)
                else -> "已耗时 ${formatInitializationDuration(currentProgress.elapsedMillis)} · 正在估算剩余时间"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatInitializationCount(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.CHINA).format(value)

private fun formatRemainingDuration(millis: Long): String =
    if (millis < 1_000) {
        "预计剩余不到 1 秒"
    } else {
        "预计剩余约 ${formatInitializationDuration(millis)}"
    }

private fun formatInitializationDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return when {
        seconds < 1 -> "不到 1 秒"
        seconds < 60 -> "$seconds 秒"
        seconds % 60 == 0L -> "${seconds / 60} 分钟"
        else -> "${seconds / 60} 分 ${seconds % 60} 秒"
    }
}
