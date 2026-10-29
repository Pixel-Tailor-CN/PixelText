package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.hub.SampleReportStatus
import vip.mystery0.pixel.text.ui.component.ExpressivePullToRefreshBox
import vip.mystery0.pixel.text.viewmodel.SampleReportListViewModel

internal const val SAMPLE_REPORT_REFRESH_REQUESTED = "sample_report_refresh_requested"

@OptIn(ExperimentalLayoutApi::class)
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
            delay((state.retryAt - System.currentTimeMillis()).coerceAtLeast(0))
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
        SampleReportTopBar("我的上报", onNavigateBack) { TextButton(onClick = onNewReport) { Text("上报") } }
    }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        ExpressivePullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                if (state.hasPending) item(key = "pending") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text("有一条提交尚未确认", style = MaterialTheme.typography.titleSmall)
                        Text("这是本机保留的请求，不是审核记录", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onNewReport) { Text("恢复本次提交") }
                        HorizontalDivider()
                    }
                }
                state.pendingError?.let { message -> item(key = "pending_error") {
                    SampleReportError(message, 0, { viewModel.refreshPending() })
                } }
                state.error?.let { message -> item(key = "error") { SampleReportError(message, state.retryAt, viewModel::refresh) } }
                if (state.loaded && state.items.isEmpty() && state.error == null) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("暂无上报记录", style = MaterialTheme.typography.titleMedium)
                        Text("可以从短信详情长按单条短信，快速带入并上报脱敏样本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onNewReport) { Text("上报样本") }
                    }
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
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 宽度不足时状态自然换行，避免大字号挤压分类。
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(sampleReportCategory(report.submittedCategory), style = MaterialTheme.typography.titleMedium)
                                    SampleReportStatusLabel(report.status)
                                }
                                Text(when {
                                    report.id in state.unavailableReportIds -> "无法查看这条上报记录"
                                    report.status == SampleReportStatus.REMOVED -> "样本已移除，内容不再提供"
                                    else -> report.contentPreview.ifBlank { "暂无摘要" }
                                },
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(sampleReportDate(report.submittedAt), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                }
                item(key = "info") {
                    TextButton(onClick = { showInfo = true }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("记录说明") }
                }
            }
        }
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("记录说明") },
        text = { Text("上报记录存储于服务端，与本次应用安装关联，卸载或清除数据后将无法访问。历史记录仅代表服务端保留的历史数据。审核通过不代表已用于训练或发布规则。") },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } })
}
