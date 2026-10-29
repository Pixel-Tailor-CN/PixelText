package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.repository.SampleSubmissionRepository
import vip.mystery0.pixel.text.domain.hub.*

data class SampleReportListState(
    val items: List<SampleReportSummary> = emptyList(),
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val nextCursor: String? = null,
    val error: String? = null,
    val appendError: String? = null,
    val hasPending: Boolean = false,
    val pendingError: String? = null,
    val retryAt: Long = 0,
    val unavailableReportIds: Set<String> = emptySet(),
)

internal fun Throwable.reportMessage(): String = (this as? SampleReportException)?.message
    ?: "网络或本机存储暂不可用，请稍后重试"

internal fun Throwable.reportRetryAt(): Long = (this as? SampleReportException)?.retryAfterSeconds
    ?.coerceIn(1, 86400)?.let { System.currentTimeMillis() + it * 1000 } ?: 0

class SampleReportListViewModel(private val repository: SampleSubmissionRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SampleReportListState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private val detailUpdates = mutableMapOf<String, SampleReportSummary>()
    private val detailUnavailable = mutableSetOf<String>()

    fun loadIfNeeded() {
        val current = state.value
        if (!current.loaded && !current.refreshing && current.error == null) refresh()
    }

    fun refresh() { startRefresh() }
    fun refreshAfterSubmission(): Boolean = startRefresh()

    private fun startRefresh(): Boolean {
        if (System.currentTimeMillis() < state.value.retryAt) return false
        val current = ++generation
        refreshJob?.cancel()
        pageJob?.cancel()
        detailUpdates.clear()
        detailUnavailable.clear()
        mutableState.update { it.copy(refreshing = true, loadingMore = false, error = null, appendError = null) }
        refreshJob = viewModelScope.launch {
            try {
                val page = repository.list()
                if (generation == current) mutableState.update { it.copy(items = page.items.distinctBy { row -> row.id }.map(::applyDetailUpdate),
                    nextCursor = page.nextCursor, refreshing = false, loaded = true, retryAt = 0,
                    unavailableReportIds = detailUnavailable.toSet()) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == current) mutableState.update { it.copy(refreshing = false,
                    error = error.reportMessage(), retryAt = error.reportRetryAt()) }
            }
        }
        refreshPending()
        return true
    }

    /** 详情只更新原行，不截短累计分页或改变游标，稳定 ID 和提交月份仍用原位置。 */
    fun updateReport(report: SampleReportSummary) {
        detailUpdates[report.id] = report
        detailUnavailable.remove(report.id)
        mutableState.update { current -> current.copy(
            items = current.items.map(::applyDetailUpdate),
            unavailableReportIds = current.unavailableReportIds - report.id,
        ) }
    }

    fun markReportUnavailable(id: String) {
        detailUpdates.remove(id)
        detailUnavailable.add(id)
        mutableState.update { current -> current.copy(
            items = current.items.map(::applyDetailUpdate),
            unavailableReportIds = current.unavailableReportIds + id,
        ) }
    }

    /** 同一查询代次内，后取得的详情结果覆盖仍在途的列表摘要；新刷新重新以服务端为准。 */
    private fun applyDetailUpdate(old: SampleReportSummary): SampleReportSummary {
        if (old.id in detailUnavailable) return old.copy(contentPreview = "", status = SampleReportStatus.UNKNOWN,
            reviewedCategory = "", reviewedAt = null, reviewMethod = "", reasonCode = "REPORT_NOT_FOUND",
            reasonText = "无法查看这条上报记录")
        val report = detailUpdates[old.id] ?: return old
        return report.copy(submittedAt = old.submittedAt,
            contentPreview = if (report.status == SampleReportStatus.REMOVED) "" else report.contentPreview)
    }

    fun refreshPending() = viewModelScope.launch {
        try {
            val pending = repository.pending() != null
            mutableState.update { it.copy(hasPending = pending, pendingError = null) }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { mutableState.update { it.copy(pendingError = error.reportMessage()) } }
    }

    fun loadMore() {
        val before = state.value
        val cursor = before.nextCursor ?: return
        if (before.refreshing || before.loadingMore || System.currentTimeMillis() < before.retryAt) return
        val current = generation
        mutableState.update { it.copy(loadingMore = true, appendError = null) }
        pageJob = viewModelScope.launch {
            try {
                val page = repository.list(cursor)
                if (generation == current) mutableState.update { it.copy(
                    items = (it.items + page.items).distinctBy { row -> row.id }.map(::applyDetailUpdate),
                    nextCursor = page.nextCursor,
                    loadingMore = false,
                    appendError = if (page.nextCursor == cursor) "分页未能前进，请下拉刷新后重试" else null,
                    retryAt = 0,
                ) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == current) mutableState.update { it.copy(loadingMore = false,
                    appendError = error.reportMessage(), retryAt = error.reportRetryAt()) }
            }
        }
    }
}
