package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.model.mms.MmsContentModel
import vip.mystery0.pixel.text.domain.repository.MmsContentRepository

class MmsContentViewModel(private val repository: MmsContentRepository) : ViewModel() {
    private val mutableContent = MutableStateFlow<MmsContentModel?>(null)
    val content = mutableContent.asStateFlow()
    private val mutableLoaded = MutableStateFlow(false)
    val loaded = mutableLoaded.asStateFlow()
    private var key: SourceMessageKey? = null
    private var subscription: Job? = null
    fun stop() { subscription?.cancel(); subscription = null; mutableContent.value = null }
    fun load(key: SourceMessageKey) {
        if (this.key == key && subscription?.isActive == true) return
        this.key = key
        subscription?.cancel()
        mutableContent.value = null
        mutableLoaded.value = false
        subscription = viewModelScope.launch {
            repository.observe(key).collect { mutableContent.value = it; mutableLoaded.value = true }
        }
    }
}
