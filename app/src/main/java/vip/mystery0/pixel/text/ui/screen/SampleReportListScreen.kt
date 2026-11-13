package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Outbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.hub.SampleReportStatus
import vip.mystery0.pixel.text.ui.component.ExpressivePullToRefreshBox
import vip.mystery0.pixel.text.ui.component.AppEmptyState
import vip.mystery0.pixel.text.viewmodel.SampleReportListViewModel

internal const val SAMPLE_REPORT_REFRESH_REQUESTED = "sample_report_refresh_requested"

@Composable
fun SampleReportListScreen(
    onNavigateBack: () -> Unit,
    onNewReport: () -> Unit,
    onOpenReport: (String) -> Unit,
    refreshRequested: Boolean = false,
    onRefreshRequestConsumed: () -> Unit = {},
    viewModel: SampleReportListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showInfo by remember { mutableStateOf(false) }
    SampleReportOnResume { viewModel.loadIfNeeded(); viewModel.refreshPending() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(refreshRequested, state.retryAt, lifecycleState) {
        if (refreshRequested && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            // 提交成功的刷新明确接线；限流结束前保留请求，离开前台时取消等待。
            delay((state.retryAt - System.currentTimeMillis()).coerceAtLeast(0).milliseconds)
            if (viewModel.refreshAfterSubmission()) onRefreshRequestConsumed()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .distinctUntilChanged().collect { (last, total) ->
                val current = viewModel.state.value
                if (last != null && total > 0 && last >= total - 3 && current.appendError == null && current.error == null) viewModel.loadMore()
            }
    }
    Scaffold(topBar = {
        SampleReportTopBar(stringResource(R.string.conv_sample_my_reports), onNavigateBack) {
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.conv_sample_about_reports))
            }
        }
    }, floatingActionButton = {
        if (!state.hasPending) {
            ExtendedFloatingActionButton(
                onClick = onNewReport,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.conv_menu_contribute_sample)) },
            )
        }
    }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        ExpressivePullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 104.dp)) {
                if (state.hasPending) item(key = "pending") {
                    Surface(onClick = onNewReport, shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Outbox, contentDescription = null)
                            Text(stringResource(R.string.conv_sample_pending_submission), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
                state.pendingError?.let { message -> item(key = "pending_error") {
                    SampleReportError(message, 0) { viewModel.refreshPending() }
                } }
                state.error?.let { message -> item(key = "error") { SampleReportError(message, state.retryAt, viewModel::refresh) } }
                if (state.loaded && state.items.isEmpty() && state.error == null) item(key = "empty") {
                    AppEmptyState(
                        text = stringResource(R.string.conv_sample_empty_reports),
                        modifier = Modifier.fillParentMaxHeight(0.75f),
                        imageRes = R.drawable.illustration_archived_conversation_empty,
                    )
                }
                state.items.forEachIndexed { index, report ->
                    val month = sampleReportMonth(report.submittedAt)
                    if (index == 0 || sampleReportMonth(state.items[index - 1].submittedAt) != month) item(key = "month_${report.id}") {
                        Text(month, Modifier.padding(top = 24.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item(key = "report_${report.id}") {
                        Row(Modifier.fillMaxWidth().clickable { onOpenReport(report.id) }.padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(when {
                                    report.id in state.unavailableReportIds -> stringResource(R.string.conv_sample_unavailable_report)
                                    report.status == SampleReportStatus.REMOVED -> stringResource(R.string.conv_sample_removed_preview)
                                    else -> report.contentPreview.ifBlank { stringResource(R.string.conv_sample_no_preview) }
                                },
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text("${sampleReportCategory(report.submittedCategory)} · ${sampleReportDate(report.submittedAt)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SampleReportStatusLabel(report.status)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (state.loadingMore) item(key = "loading_more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                state.appendError?.let { message -> item(key = "append_error") {
                    SampleReportError(message, state.retryAt, viewModel::loadMore)
                } }
                if (state.nextCursor != null && !state.loadingMore && state.appendError == null && !state.refreshing) item(key = "load_more") {
                    TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conv_action_load_more)) }
                }
            }
        }
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text(stringResource(R.string.conv_sample_about_reports)) },
        text = { Text(stringResource(R.string.conv_sample_about_reports_desc)) },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.conv_action_got_it)) } })
}
