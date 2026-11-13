package vip.mystery0.pixel.text.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.SupportChannel
import vip.mystery0.pixel.text.domain.model.SupportChannelId
import vip.mystery0.pixel.text.domain.repository.DeveloperSupportRepository
import vip.mystery0.pixel.text.util.AppStrings
import java.io.IOException

data class SupportQrState(
    val loading: Boolean = true,
    val bitmap: Bitmap? = null,
    val bytes: ByteArray? = null,
    val error: Boolean = false,
    val saving: Boolean = false,
)

data class DeveloperSupportUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val enabled: Boolean = true,
    val channels: List<SupportChannel> = emptyList(),
    val images: Map<SupportChannelId, SupportQrState> = emptyMap(),
)

class DeveloperSupportViewModel(private val repository: DeveloperSupportRepository) : ViewModel() {
    private val _state = MutableStateFlow(DeveloperSupportUiState())
    val state = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var loadJob: Job? = null

    init { reload() }

    fun reload() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.value = DeveloperSupportUiState()
            try {
                val config = repository.loadConfig()
                _state.value = DeveloperSupportUiState(
                    loading = false, enabled = config.enabled, channels = config.channels,
                    images = config.channels.associate { it.id to SupportQrState() },
                )
                coroutineScope {
                    config.channels.forEach { channel -> launch { loadImage(channel) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = DeveloperSupportUiState(loading = false, error = true)
            }
        }
    }

    fun retryImage(channel: SupportChannel) {
        if (_state.value.images[channel.id]?.loading != false) return
        updateImage(channel.id) { SupportQrState() }
        viewModelScope.launch { loadImage(channel) }
    }

    private suspend fun loadImage(channel: SupportChannel) {
        try {
            val bytes = repository.loadQrCode(channel)
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("cannot decode support image")
            }
            updateImage(channel.id) { SupportQrState(loading = false, bitmap = bitmap, bytes = bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            updateImage(channel.id) { SupportQrState(loading = false, error = true) }
        }
    }

    fun saveImage(channel: SupportChannel) {
        val image = _state.value.images[channel.id] ?: return
        val bytes = image.bytes ?: return
        if (image.saving) return
        updateImage(channel.id) { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                repository.saveQrCode(channel, bytes)
                _message.value = AppStrings.get(R.string.sys_support_qr_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _message.value = AppStrings.get(R.string.sys_support_qr_save_failed)
            } finally {
                updateImage(channel.id) { it.copy(saving = false) }
            }
        }
    }

    fun showMessage(message: String) { _message.value = message }
    fun consumeMessage() { _message.value = null }

    private fun updateImage(id: SupportChannelId, transform: (SupportQrState) -> SupportQrState) {
        _state.update { current ->
            val image = current.images[id] ?: return@update current
            current.copy(images = current.images + (id to transform(image)))
        }
    }
}
