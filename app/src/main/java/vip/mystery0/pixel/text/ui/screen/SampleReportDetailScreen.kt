package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.hub.SampleReportStatus
import vip.mystery0.pixel.text.domain.hub.SampleReportSummary
import vip.mystery0.pixel.text.ui.component.ExpressivePullToRefreshBox
import vip.mystery0.pixel.text.viewmodel.SampleReportDetailViewModel

@Composable
fun SampleReportDetailScreen(reportId: String, onNavigateBack: () -> Unit,
    onReportLoaded: (SampleReportSummary) -> Unit = {},
    onReportNotFound: (String) -> Unit = {},
    viewModel: SampleReportDetailViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val retrySeconds = sampleReportRetrySeconds(state.retryAt)
    SampleReportOnResume { viewModel.refresh(reportId) }
    LaunchedEffect(state.report, state.notFound) {
        state.report?.summary?.let(onReportLoaded)
        if (state.notFound) onReportNotFound(reportId)
    }
    Scaffold(topBar = {
        SampleReportTopBar("上报详情", onNavigateBack) {
            TextButton(onClick = { viewModel.refresh(reportId) }, enabled = !state.loading && retrySeconds == 0L) { Text("刷新") }
        }
    }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        ExpressivePullToRefreshBox(isRefreshing = state.loading, onRefresh = { viewModel.refresh(reportId) },
            modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                state.error?.let { message -> item { SampleReportError(message, state.retryAt, { viewModel.refresh(reportId) }) } }
                state.report?.let { report ->
                    val summary = report.summary
                    item {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SampleReportStatusLabel(summary.status)
                                Text(if (summary.status == SampleReportStatus.REMOVED) "样本已移除，内容不再提供"
                                    else summary.reasonText.ifBlank { "审核状态待确认" })
                                if (summary.status == SampleReportStatus.APPROVED) Text("审核通过不代表已用于训练或发布规则",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (summary.status != SampleReportStatus.REMOVED) {
                        item { SelectionContainer { SampleReportField("脱敏正文", report.submittedContent.ifBlank { "暂无正文" }) }; HorizontalDivider() }
                        item { SelectionContainer { SampleReportField("发送方", report.submittedSender.ifBlank { "未提供发送方" }) }; HorizontalDivider() }
                    }
                    item { SampleReportField("提交分类", sampleReportCategory(summary.submittedCategory)) }
                    if (summary.reviewedCategory.isNotBlank()) item {
                        SampleReportField(if (summary.reviewedCategory != summary.submittedCategory) "审核分类（与提交分类不同）" else "审核分类",
                            sampleReportCategory(summary.reviewedCategory))
                    }
                    item { HorizontalDivider(); SampleReportField("上报时间", sampleReportDate(summary.submittedAt)) }
                    item { SampleReportField("状态更新时间", sampleReportDate(summary.statusUpdatedAt)) }
                    summary.reviewedAt?.let { date -> item { SampleReportField("审核时间", sampleReportDate(date)) } }
                    if (summary.reviewMethod.isNotBlank()) item { SampleReportField("审核方式", when (summary.reviewMethod) {
                        "ai" -> "自动审核"; "manual" -> "人工审核"; else -> "方式待确认"
                    }) }
                    item {
                        HorizontalDivider()
                        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("记录编号：${summary.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(when (summary.source) {
                                "legacy" -> "历史记录：正文、发送方和分类为服务端保留的历史数据"
                                "app" -> "来源：应用上报"
                                else -> "来源待确认"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (state.loading && state.report == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
