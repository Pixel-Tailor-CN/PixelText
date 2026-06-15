package vip.mystery0.pixel.text.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.repository.SampleSubmissionRepository
import vip.mystery0.pixel.text.domain.hub.HubOperationResult

class SampleSubmissionViewModel(
    private val repository: SampleSubmissionRepository,
) : ViewModel() {
    var content by mutableStateOf("")
        private set
    var sender by mutableStateOf("")
        private set
    var category by mutableStateOf(DEFAULT_CATEGORY)
        private set
    var agreed by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set
    private var draftApplied = false

    fun updateContent(value: String) {
        content = value
    }

    fun updateSender(value: String) {
        sender = value
    }

    fun updateCategory(value: String) {
        category = value
    }

    fun updateAgreed(value: Boolean) {
        agreed = value
    }

    fun applyDraft(
        content: String,
        sender: String,
        category: String = DRAFT_CATEGORY
    ) {
        if (draftApplied) return
        if (content.isBlank() && sender.isBlank()) return
        draftApplied = true
        this.content = content
        this.sender = sender
        this.category = category
        agreed = false
        resultMessage = null
    }

    fun clearResult() {
        resultMessage = null
    }

    fun submit() {
        if (!agreed || submitting || content.isBlank()) return
        submitting = true
        viewModelScope.launch {
            val result = runCatching {
                repository.submit(content, sender, category)
            }.getOrElse { error ->
                HubOperationResult.Failure(error.message ?: "提交失败")
            }
            resultMessage = when (result) {
                HubOperationResult.Success -> "样本已上报"
                is HubOperationResult.Failure -> result.message
            }
            submitting = false
        }
    }

    private companion object {
        private const val DEFAULT_CATEGORY = "verification_code"
        private const val DRAFT_CATEGORY = "normal"
    }
}
