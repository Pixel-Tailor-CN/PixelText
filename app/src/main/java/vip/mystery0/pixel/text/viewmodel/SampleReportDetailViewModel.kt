package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.repository.SampleSubmissionRepository
import vip.mystery0.pixel.text.domain.hub.*

data class SampleReportDetailState(
    val report: SampleReportDetail? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val retryAt: Long = 0,
    val notFound: Boolean = false,
)

class SampleReportDetailViewModel(private val repository: SampleSubmissionRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SampleReportDetailState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    fun refresh(id: String) {
        if (System.currentTimeMillis() < state.value.retryAt) return
        val current = ++generation
        job?.cancel()
        // 查询开始即移除旧快照，404、移除或失败时都不回显上次正文。
        mutableState.value = SampleReportDetailState(loading = true)
        job = viewModelScope.launch {
            try {
                if (!isValidSampleReportId(id)) throw SampleReportException("REPORT_NOT_FOUND", "无法查看这条上报记录")
                val report = repository.detail(id)
                if (generation == current) mutableState.value = SampleReportDetailState(report = report)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == current) mutableState.value = SampleReportDetailState(
                    error = error.reportMessage(), retryAt = error.reportRetryAt(),
                    notFound = (error as? SampleReportException)?.code in setOf("REPORT_NOT_FOUND", "HTTP_404"))
            }
        }
    }
}
