package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.repository.SampleSubmissionRepository
import vip.mystery0.pixel.text.domain.hub.*
import vip.mystery0.pixel.text.domain.sample.*

data class SampleSubmissionDraftChoice(
    val currentMessage: SampleReportDraft,
    val retainedDraft: SampleReportDraft,
)

data class SampleSubmissionState(
    val content: String = "",
    val sender: String = "",
    val category: String = SampleCategory.NORMAL.value,
    val agreed: Boolean = false,
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val preparing: Boolean = false,
    val saving: Boolean = false,
    val pending: Boolean = false,
    val localError: Boolean = false,
    val dirty: Boolean = false,
    val hasRetainedDraft: Boolean = false,
    val draftChoice: SampleSubmissionDraftChoice? = null,
    val resultMessage: String? = null,
    val successReportId: String? = null,
    val retryAt: Long = 0,
    val desensitizationHintMessage: String? = null,
    val desensitizationState: DesensitizationAssistantState = DesensitizationAssistantState(),
) {
    val locked: Boolean get() = loading || submitting || saving || pending || localError || draftChoice != null
    val validationError: String? get() = SampleSubmissionRepository.validationError(content, sender, category)
}

class SampleSubmissionViewModel(
    private val repository: SampleSubmissionRepository,
    private val desensitizationAssistant: SampleDesensitizationAssistant = SampleDesensitizationAssistant(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(SampleSubmissionState())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var initialInput: SampleReportDraft? = null

    fun initialize(content: String, sender: String, category: String) {
        if (initialized) return
        initialized = true
        if (initialInput == null) initialInput = SampleReportDraft(content, sender, category)
        mutableState.update { it.copy(loading = true, localError = false) }
        viewModelScope.launch {
            try {
                val pending = repository.pending()
                val draft = if (pending == null) repository.draft() else null
                val currentMessage = SampleReportDraft(content, sender,
                    SampleCategory.fromValue(category)?.value ?: SampleCategory.NORMAL.value)
                mutableState.value = if (pending != null) SampleSubmissionState(
                    content = pending.content, sender = pending.sender, category = pending.category,
                    agreed = true, pending = true, loading = false,
                    resultMessage = "暂未确认提交结果。已恢复本次确认的内容和请求编号；查看记录不会自动结束本次提交",
                ) else if (draft != null && (content.isNotBlank() || sender.isNotBlank())) {
                    // 只在内存中等待用户选择，不展示原文摘要、不覆盖原有保留文件。
                    SampleSubmissionState(loading = false, hasRetainedDraft = true,
                        draftChoice = SampleSubmissionDraftChoice(currentMessage, draft))
                } else SampleSubmissionState(
                    content = draft?.content ?: content, sender = draft?.sender ?: sender,
                    category = SampleCategory.fromValue(draft?.category ?: category)?.value ?: SampleCategory.NORMAL.value,
                    loading = false, dirty = draft != null, hasRetainedDraft = draft != null,
                )
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                initialized = false
                mutableState.update { it.copy(loading = false, localError = true, resultMessage = error.reportMessage()) }
            }
        }
    }

    /** 重读与首次预填分开，提交后恢复只检查原请求，不重新套用最初短信或普通草稿。 */
    fun retryLocalRead() {
        val before = state.value
        if (!before.localError || before.loading || before.submitting || before.saving) return
        if (!initialized) {
            val input = initialInput ?: return
            initialize(input.content, input.sender, input.category)
            return
        }
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val pending = repository.pending()
                mutableState.value = if (pending != null) before.copy(content = pending.content,
                    sender = pending.sender, category = pending.category, agreed = true, pending = true,
                    loading = false, preparing = false, localError = false, draftChoice = null,
                    resultMessage = "已恢复本次待确认提交，将继续使用原请求编号和全部内容")
                else before.copy(loading = false, preparing = false, localError = false, pending = false,
                    resultMessage = "本机数据已重新读取，可继续编辑或提交")
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, localError = true, resultMessage = error.reportMessage()) }
            }
        }
    }

    fun showPreparingHint() {
        mutableState.update { it.copy(resultMessage = if (it.preparing) "正在保存本次提交，请稍候再返回" else "正在读取或保存本机数据，请稍候再返回") }
    }

    fun chooseCurrentMessage() = chooseDraft(useCurrentMessage = true)
    fun chooseRetainedDraft() = chooseDraft(useCurrentMessage = false)
    private fun chooseDraft(useCurrentMessage: Boolean) {
        val choice = state.value.draftChoice ?: return
        val draft = if (useCurrentMessage) choice.currentMessage else choice.retainedDraft
        // 选择只改变本页内容；原保留草稿直到显式保存、放弃或提交成功才按流程清理。
        mutableState.value = SampleSubmissionState(content = draft.content, sender = draft.sender,
            category = SampleCategory.fromValue(draft.category)?.value ?: SampleCategory.NORMAL.value,
            loading = false, dirty = !useCurrentMessage, hasRetainedDraft = true)
    }

    fun updateContent(value: String) = edit { it.copy(content = value) }
    fun updateSender(value: String) = edit { it.copy(sender = value) }
    fun updateCategory(value: String) = edit { it.copy(category = value) }
    private fun edit(transform: (SampleSubmissionState) -> SampleSubmissionState) {
        if (state.value.locked) return
        mutableState.update { transform(it).copy(agreed = false, dirty = true, resultMessage = null) }
    }
    fun updateAgreed(value: Boolean) {
        if (!state.value.locked) mutableState.update { it.copy(agreed = value) }
    }

    fun openDesensitizationAssistant() {
        if (state.value.locked || state.value.content.isBlank()) return
        mutableState.update { it.copy(desensitizationState = desensitizationAssistant.open(it.content)) }
    }
    fun requestCloseDesensitizationAssistant(): Boolean {
        if (state.value.desensitizationState.dirty) return false
        discardDesensitizedDraft()
        return true
    }
    fun discardDesensitizedDraft() {
        mutableState.update { it.copy(desensitizationState = desensitizationAssistant.close()) }
    }
    fun selectSensitiveRange(start: Int, end: Int) {
        mutableState.update { it.copy(desensitizationState = desensitizationAssistant.selectRange(it.desensitizationState, start, end)) }
    }
    fun updateSensitiveType(type: SensitiveType) {
        mutableState.update { it.copy(desensitizationState = desensitizationAssistant.updateType(it.desensitizationState, type)) }
    }
    fun replaceSelectedSensitiveText() {
        val result = desensitizationAssistant.replaceSelected(state.value.desensitizationState)
        mutableState.update { it.copy(desensitizationState = result.state,
            desensitizationHintMessage = when (result.error) {
                SampleReplacementError.INVALID_AMOUNT_FORMAT -> "选择的内容似乎不是有效的金额格式，请手动修改"
                SampleReplacementError.INVALID_TEMPORAL_FORMAT -> "选择的内容似乎不是有效的时间格式，请手动修改"
                null -> null
            }) }
    }
    fun applyDesensitizedDraft() {
        val draft = state.value.desensitizationState.draft
        if (draft.isBlank()) return
        updateContent(draft)
        discardDesensitizedDraft()
    }
    fun clearDesensitizationHintMessage() { mutableState.update { it.copy(desensitizationHintMessage = null) } }
    fun consumeSuccess() { mutableState.update { it.copy(successReportId = null) } }

    fun submit() {
        val before = state.value
        if (before.loading || before.localError || before.submitting || before.saving || before.draftChoice != null ||
            (!before.pending && (!before.agreed || before.validationError != null)) ||
            System.currentTimeMillis() < before.retryAt) return
        mutableState.update { it.copy(submitting = true, preparing = !before.pending, dirty = true, resultMessage = null) }
        viewModelScope.launch {
            try {
                val onPersisted: (SampleReportRequest) -> Unit = { request ->
                    mutableState.update { it.copy(content = request.content, sender = request.sender,
                        category = request.category, agreed = true, pending = true, preparing = false, resultMessage = null) }
                }
                val result = if (before.pending) repository.retryPending(onPersisted)
                    else repository.submit(before.content, before.sender, before.category, onPersisted)
                mutableState.update { it.copy(submitting = false, preparing = false, pending = false, dirty = false,
                    successReportId = result.report.summary.id) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                // 不能凭异常种类推断是否已经落盘；以持久化状态锁定这次完整负载。
                try {
                    val pending = repository.pending()
                    mutableState.update { it.copy(submitting = false, preparing = false, pending = pending != null,
                        content = pending?.content ?: it.content, sender = pending?.sender ?: it.sender,
                        category = pending?.category ?: it.category,
                        resultMessage = if (pending != null) "暂未确认提交结果。${error.reportMessage()}" else error.reportMessage(),
                        retryAt = error.reportRetryAt()) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (storageError: Exception) {
                    mutableState.update { it.copy(submitting = false, preparing = false, localError = true, resultMessage = storageError.reportMessage()) }
                }
            } finally {
                mutableState.update { it.copy(submitting = false, preparing = false) }
            }
        }
    }

    fun saveAndLeave(onSaved: () -> Unit) = finishDraft(keep = true, onSaved)
    fun discardAndLeave(onDiscarded: () -> Unit) = finishDraft(keep = false, onDiscarded)
    private fun finishDraft(keep: Boolean, onDone: () -> Unit) {
        val current = state.value
        // 即使请求读取仍异常，用户也可显式另存编辑草稿；不会更改任何待确认请求。
        if (current.loading || current.submitting || current.saving || current.pending || current.draftChoice != null) return
        val draft = SampleReportDraft(state.value.content, state.value.sender, state.value.category)
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                if (keep) repository.saveDraft(draft) else repository.clearDraft()
                mutableState.update { it.copy(saving = false, dirty = false) }
                onDone()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { mutableState.update { it.copy(saving = false, resultMessage = error.reportMessage()) } }
        }
    }
}
