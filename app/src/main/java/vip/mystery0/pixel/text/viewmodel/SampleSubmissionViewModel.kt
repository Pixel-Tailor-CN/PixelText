package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.repository.SampleSubmissionRepository
import vip.mystery0.pixel.text.domain.hub.*
import vip.mystery0.pixel.text.domain.sample.*
import vip.mystery0.pixel.text.util.AppStrings

data class SampleSubmissionDraftChoice(
    val currentMessage: SampleReportDraft,
    val retainedDraft: SampleReportDraft,
)

data class SampleSubmissionState(
    val learningFeedback: LearningFeedback? = null,
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

    fun initialize(content: String, sender: String, category: String, learningFeedback: LearningFeedback? = null) {
        if (initialized) return
        initialized = true
        if (initialInput == null) initialInput = SampleReportDraft(content, sender, category, learningFeedback)
        mutableState.update { it.copy(loading = true, localError = false) }
        viewModelScope.launch {
            try {
                val pending = repository.pending()
                val draft = if (pending == null) repository.draft() else null
                // 保留短信原文与发送号码，只有用户应用辅助脱敏结果时才修改草稿。
                val currentMessage = SampleReportDraft(content, sender,
                    SampleCategory.fromValue(category)?.value ?: SampleCategory.NORMAL.value, learningFeedback)
                mutableState.value = if (pending != null) SampleSubmissionState(
                    content = pending.content, sender = pending.sender, category = pending.category,
                    learningFeedback = pending.learningFeedback,
                    agreed = true, pending = true, loading = false,
                    resultMessage = AppStrings.get(R.string.sys_sample_pending_restored_init),
                ) else if (draft != null && (content.isNotBlank() || sender.isNotBlank())) {
                    // 只在内存中等待用户选择，不展示原文摘要、不覆盖原有保留文件。
                    SampleSubmissionState(loading = false, hasRetainedDraft = true,
                        draftChoice = SampleSubmissionDraftChoice(currentMessage, draft))
                } else SampleSubmissionState(
                    content = draft?.content ?: currentMessage.content, sender = draft?.sender ?: currentMessage.sender,
                    learningFeedback = draft?.learningFeedback ?: learningFeedback,
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
            initialize(input.content, input.sender, input.category, input.learningFeedback)
            return
        }
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val pending = repository.pending()
                mutableState.value = if (pending != null) before.copy(content = pending.content,
                    sender = pending.sender, category = pending.category, learningFeedback = pending.learningFeedback, agreed = true, pending = true,
                    loading = false, preparing = false, localError = false, draftChoice = null,
                    resultMessage = AppStrings.get(R.string.sys_sample_pending_restored_retry))
                else before.copy(loading = false, preparing = false, localError = false, pending = false,
                    resultMessage = AppStrings.get(R.string.sys_sample_local_reloaded))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, localError = true, resultMessage = error.reportMessage()) }
            }
        }
    }

    fun showPreparingHint() {
        mutableState.update {
            it.copy(
                resultMessage = AppStrings.get(
                    if (it.preparing) R.string.sys_sample_saving_submission_hint
                    else R.string.sys_sample_busy_local_hint
                )
            )
        }
    }

    fun chooseCurrentMessage() = chooseDraft(useCurrentMessage = true)
    fun chooseRetainedDraft() = chooseDraft(useCurrentMessage = false)
    private fun chooseDraft(useCurrentMessage: Boolean) {
        val choice = state.value.draftChoice ?: return
        val draft = if (useCurrentMessage) choice.currentMessage else choice.retainedDraft
        // 选择只改变本页内容；原保留草稿直到显式保存、放弃或提交成功才按流程清理。
        mutableState.value = SampleSubmissionState(content = draft.content, sender = draft.sender,
            learningFeedback = draft.learningFeedback,
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
                SampleReplacementError.INVALID_AMOUNT_FORMAT -> AppStrings.get(R.string.sys_sample_invalid_amount)
                SampleReplacementError.INVALID_TEMPORAL_FORMAT -> AppStrings.get(R.string.sys_sample_invalid_temporal)
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
                        category = request.category, learningFeedback = request.learningFeedback, agreed = true, pending = true, preparing = false, resultMessage = null) }
                }
                val result = if (before.pending) repository.retryPending(onPersisted)
                    else repository.submit(before.content, before.sender, before.category, onPersisted, before.learningFeedback)
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
                        learningFeedback = if (pending != null) pending.learningFeedback else it.learningFeedback,
                        resultMessage = if (pending != null) {
                            AppStrings.get(R.string.sys_sample_pending_error_prefix, error.reportMessage())
                        } else {
                            error.reportMessage()
                        },
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
        val draft = SampleReportDraft(state.value.content, state.value.sender, state.value.category, state.value.learningFeedback)
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
