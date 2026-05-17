package vip.mystery0.pixel.text.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object DebugMode {
    private var enabled by mutableStateOf(false)

    fun enable() {
        enabled = true
    }

    fun isEnabled(): Boolean = enabled
}

fun enableDebugMode() {
    DebugMode.enable()
}

fun isDebugModeEnabled(): Boolean {
    return DebugMode.isEnabled()
}
