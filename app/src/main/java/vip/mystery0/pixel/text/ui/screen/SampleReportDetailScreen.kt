package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.hub.SampleReportStatus
import vip.mystery0.pixel.text.domain.hub.SampleReportSummary
import vip.mystery0.pixel.text.ui.component.ExpressivePullToRefreshBox
import vip.mystery0.pixel.text.viewmodel.SampleReportDetailViewModel

@Composable
fun SampleReportDetailScreen(
    reportId: String,
    onNavigateBack: () -> Unit,
    onReportLoaded: (SampleReportSummary) -> Unit = {},
    onReportNotFound: (String) -> Unit = {},
    viewModel: SampleReportDetailViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val retrySeconds = sampleReportRetrySeconds(state.retryAt)
    var showInfo by remember { mutableStateOf(false) }
    SampleReportOnResume { viewModel.refresh(reportId) }
    LaunchedEffect(state.report, state.notFound) {
        state.report?.summary?.let(onReportLoaded)
        if (state.notFound) onReportNotFound(reportId)
    }
    Scaffold(
        topBar = {
            SampleReportTopBar("上报详情", onNavigateBack) {
                IconButton(onClick = { showInfo = true }, enabled = state.report != null) {
                    Icon(Icons.Rounded.Info, contentDescription = "记录信息")
                }
                IconButton(
                    onClick = { viewModel.refresh(reportId) },
                    enabled = !state.loading && retrySeconds == 0L,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        ExpressivePullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.refresh(reportId) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                state.error?.let { message ->
                    item { SampleReportError(message, state.retryAt) { viewModel.refresh(reportId) } }
                }
                state.report?.let { report ->
                    val summary = report.summary
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SampleReportStatusLabel(summary.status, prominent = true)
                            if (summary.status == SampleReportStatus.REMOVED) {
                                Text("样本内容已移除", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (summary.reasonText.isNotBlank() && summary.reasonText != summary.status.label) {
                                Text(summary.reasonText, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(sampleReportDate(summary.statusUpdatedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                    if (summary.status != SampleReportStatus.REMOVED) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("上报内容", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer {
                                    Text(report.submittedContent.ifBlank { "暂无正文" },
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                                if (report.submittedSender.isNotBlank()) {
                                    SelectionContainer {
                                        ReportDetailMetadata("发送方", report.submittedSender)
                                    }
                                }
                            }
                        }
                        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ReportDetailMetadata("提交分类", sampleReportCategory(summary.submittedCategory))
                            if (summary.reviewedCategory.isNotBlank()) {
                                ReportDetailMetadata("审核分类", sampleReportCategory(summary.reviewedCategory))
                            }
                            ReportDetailMetadata("上报时间", sampleReportDate(summary.submittedAt))
                        }
                    }
                }
            }
            if (state.loading && state.report == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
    if (showInfo) {
        state.report?.summary?.let { summary ->
            AlertDialog(
                onDismissRequest = { showInfo = false },
                title = { Text("记录信息") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SelectionContainer { SampleReportField("记录编号", summary.id) }
                        summary.reviewedAt?.let { SampleReportField("审核时间", sampleReportDate(it)) }
                        if (summary.reviewMethod.isNotBlank()) {
                            SampleReportField("审核方式", when (summary.reviewMethod) {
                                "ai" -> "自动审核"
                                "manual" -> "人工审核"
                                else -> "待确认"
                            })
                        }
                        SampleReportField("来源", when (summary.source) {
                            "legacy" -> "历史上报"
                            "app" -> "应用上报"
                            else -> "待确认"
                        })
                    }
                },
                confirmButton = { TextButton(onClick = { showInfo = false }) { Text("关闭") } },
            )
        }
    }
}

@Composable
private fun ReportDetailMetadata(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End)
    }
}
