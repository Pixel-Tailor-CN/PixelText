package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.settings.AppIdentity
import vip.mystery0.pixel.text.domain.settings.AppIdentityRepository
import vip.mystery0.pixel.text.util.AppStrings

data class AppIdentityUpdateState(
    val isApplying: Boolean = false,
    val applied: Boolean = false,
    val error: String? = null,
)

class AppIdentityViewModel(
    private val repository: AppIdentityRepository,
) : ViewModel() {
    val identity = repository.identity
    private val _updateState = MutableStateFlow(AppIdentityUpdateState())
    val updateState = _updateState.asStateFlow()

    fun resetResult() {
        if (!_updateState.value.isApplying) _updateState.value = AppIdentityUpdateState()
    }

    fun apply(identity: AppIdentity) {
        if (_updateState.value.isApplying) return
        _updateState.value = AppIdentityUpdateState(isApplying = true)
        viewModelScope.launch {
            _updateState.value = if (repository.apply(identity).isSuccess) {
                AppIdentityUpdateState(applied = true)
            } else {
                AppIdentityUpdateState(error = AppStrings.get(R.string.sys_app_identity_apply_failed))
            }
        }
    }
}
