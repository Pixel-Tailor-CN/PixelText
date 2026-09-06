package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.mirror.MirrorMessageModel
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository

class MirrorMessageDetailViewModel(private val repository: MessageMirrorRepository) : ViewModel() {
    private val _message = MutableStateFlow<MirrorMessageModel?>(null)
    val message = _message.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    private var observedKey: SourceMessageKey? = null
    private var observation: kotlinx.coroutines.Job? = null
    fun load(key: SourceMessageKey) {
        if (observedKey == key) return
        observedKey = key
        observation?.cancel()
        _loaded.value = false
        _message.value = null
        observation = viewModelScope.launch {
            repository.observeMessage(key).collect { row ->
                _message.value = row
                _loaded.value = true
            }
        }
    }
}
