package vip.mystery0.pixel.text.domain.sample

data class DesensitizationAssistantState(
    val visible: Boolean = false,
    val draft: String = "",
    val tokens: List<SampleTextToken> = emptyList(),
    val selectedStart: Int? = null,
    val selectedEnd: Int? = null,
    val selectedType: SensitiveType? = null,
    val dirty: Boolean = false,
) {
    val canReplace: Boolean
        get() = selectedStart != null &&
            selectedEnd != null &&
            selectedStart < selectedEnd &&
            selectedType != null

    fun isTokenSelected(token: SampleTextToken): Boolean {
        val start = selectedStart ?: return false
        val end = selectedEnd ?: return false
        return token.start >= start && token.end <= end
    }
}

data class ReplaceSelectedResult(
    val state: DesensitizationAssistantState,
    val error: SampleReplacementError? = null,
)

class SampleDesensitizationAssistant(
    private val tokenizer: SampleTextTokenizer = SampleTextTokenizer(),
    private val desensitizer: SampleDesensitizer = SampleDesensitizer(),
) {
    fun open(content: String): DesensitizationAssistantState {
        return DesensitizationAssistantState(
            visible = true,
            draft = content,
            tokens = tokenizer.selectionUnits(content),
        )
    }

    fun close(): DesensitizationAssistantState {
        return DesensitizationAssistantState()
    }

    fun selectRange(
        state: DesensitizationAssistantState,
        start: Int,
        end: Int,
    ): DesensitizationAssistantState {
        if (!state.visible || start < 0 || end > state.draft.length || start >= end) {
            return state
        }
        val selectedStart = state.selectedStart
        val selectedEnd = state.selectedEnd
        if (selectedStart != null &&
            selectedEnd != null &&
            start >= selectedStart &&
            end <= selectedEnd
        ) {
            return state.copy(
                selectedStart = start,
                selectedEnd = end,
            )
        }
        val nextStart = if (selectedStart == null || selectedEnd == null) {
            start
        } else {
            minOf(selectedStart, start)
        }
        val nextEnd = if (selectedStart == null || selectedEnd == null) {
            end
        } else {
            maxOf(selectedEnd, end)
        }
        return state.copy(
            selectedStart = nextStart,
            selectedEnd = nextEnd,
        )
    }

    fun updateType(
        state: DesensitizationAssistantState,
        type: SensitiveType,
    ): DesensitizationAssistantState {
        if (!state.visible) return state
        return state.copy(selectedType = type)
    }

    fun replaceSelected(state: DesensitizationAssistantState): ReplaceSelectedResult {
        val start = state.selectedStart ?: return ReplaceSelectedResult(state)
        val end = state.selectedEnd ?: return ReplaceSelectedResult(state)
        val type = state.selectedType ?: return ReplaceSelectedResult(state)
        if (start >= end) return ReplaceSelectedResult(state)
        return when (val result = desensitizer.replace(state.draft, start, end, type)) {
            is SampleReplacementResult.Success -> {
                val nextDraft = result.content
                ReplaceSelectedResult(
                    state = state.copy(
                        draft = nextDraft,
                        tokens = tokenizer.selectionUnits(nextDraft),
                        selectedStart = null,
                        selectedEnd = null,
                        dirty = nextDraft != state.draft || state.dirty,
                    )
                )
            }

            is SampleReplacementResult.Failure -> {
                ReplaceSelectedResult(
                    state = state,
                    error = result.error,
                )
            }
        }
    }
}
