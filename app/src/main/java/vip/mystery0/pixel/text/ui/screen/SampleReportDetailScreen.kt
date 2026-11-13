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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
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
            SampleReportTopBar(stringResource(R.string.conv_sample_detail_title), onNavigateBack) {
                IconButton(onClick = { showInfo = true }, enabled = state.report != null) {
                    Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.conv_sample_record_info))
                }
                IconButton(
                    onClick = { viewModel.refresh(reportId) },
                    enabled = !state.loading && retrySeconds == 0L,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.conv_cd_refresh))
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
                                Text(stringResource(R.string.conv_sample_content_removed), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(stringResource(R.string.conv_sample_reported_content), style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer {
                                    Text(report.submittedContent.ifBlank { stringResource(R.string.conv_sample_no_body) },
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                                if (report.submittedSender.isNotBlank()) {
                                    SelectionContainer {
                                        ReportDetailMetadata(stringResource(R.string.conv_sample_sender), report.submittedSender)
                                    }
                                }
                            }
                        }
                        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ReportDetailMetadata(stringResource(R.string.conv_sample_submitted_category), sampleReportCategory(summary.submittedCategory))
                            if (summary.reviewedCategory.isNotBlank()) {
                                ReportDetailMetadata(stringResource(R.string.conv_sample_reviewed_category), sampleReportCategory(summary.reviewedCategory))
                            }
                            ReportDetailMetadata(stringResource(R.string.conv_sample_submitted_at), sampleReportDate(summary.submittedAt))
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
                title = { Text(stringResource(R.string.conv_sample_record_info)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SelectionContainer { SampleReportField(stringResource(R.string.conv_sample_record_id), summary.id) }
                        summary.reviewedAt?.let { SampleReportField(stringResource(R.string.conv_sample_reviewed_at), sampleReportDate(it)) }
                        if (summary.reviewMethod.isNotBlank()) {
                            SampleReportField(
                                stringResource(R.string.conv_sample_review_method),
                                stringResource(
                                    when (summary.reviewMethod) {
                                        "ai" -> R.string.conv_sample_review_ai
                                        "manual" -> R.string.conv_sample_review_manual
                                        else -> R.string.conv_sample_pending_confirm
                                    }
                                ),
                            )
                        }
                        SampleReportField(
                            stringResource(R.string.conv_sample_source),
                            stringResource(
                                when (summary.source) {
                                    "legacy" -> R.string.conv_sample_source_legacy
                                    "app" -> R.string.conv_sample_source_app
                                    else -> R.string.conv_sample_pending_confirm
                                }
                            ),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.conv_action_close)) } },
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
