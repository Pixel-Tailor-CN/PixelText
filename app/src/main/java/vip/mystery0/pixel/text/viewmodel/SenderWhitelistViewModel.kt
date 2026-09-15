package vip.mystery0.pixel.text.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRule
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration

data class WhitelistEditor(
    val id: Long? = null,
    val type: WhitelistRuleType = WhitelistRuleType.EXACT,
    val input: String = "",
    val testSender: String = "",
)

data class WhitelistUiState(
    val editor: WhitelistEditor? = null,
    val deleting: SenderWhitelistRule? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class SenderWhitelistViewModel(
    private val repository: SenderWhitelistRepository,
    application: Application,
) : AndroidViewModel(application) {
    val rules = repository.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _state = MutableStateFlow(WhitelistUiState())
    val state = _state.asStateFlow()

    fun openEditor(rule: SenderWhitelistRule? = null, sender: String = "") {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            editor = WhitelistEditor(rule?.id, rule?.type ?: WhitelistRuleType.EXACT, rule?.value ?: sender),
            deleting = null, error = null,
        )
    }

    fun updateEditor(editor: WhitelistEditor) {
        if (!_state.value.busy) _state.value = _state.value.copy(editor = editor, error = null)
    }

    fun requestDelete(rule: SenderWhitelistRule) {
        if (!_state.value.busy) _state.value = _state.value.copy(deleting = rule, error = null)
    }

    fun dismissDialog() {
        if (!_state.value.busy) _state.value = _state.value.copy(editor = null, deleting = null, error = null)
    }

    fun save() {
        val editor = _state.value.editor ?: return
        execute("规则已保存，匹配的已有消息已放行") {
            repository.save(editor.id, editor.type, editor.input)
        }
    }

    fun confirmDelete() {
        val rule = _state.value.deleting ?: return
        execute("规则已删除，已放行消息保持非骚扰") { repository.delete(rule.id) }
    }

    private fun execute(success: String, operation: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                operation()
                _state.value = WhitelistUiState(message = success)
                SmartspacerIntegration.notifyChanged(getApplication<Application>())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "操作失败，请稍后重试")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
