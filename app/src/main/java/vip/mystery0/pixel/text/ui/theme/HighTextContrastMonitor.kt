package vip.mystery0.pixel.text.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "HighTextContrastMonitor"

// Secure setting used by Android high text contrast accessibility option.
// Public AccessibilityManager high-contrast APIs are unavailable on the
// android-37 SDK surface used by this project, so observe the secure setting.
private const val HIGH_TEXT_CONTRAST_SETTING = "high_text_contrast_enabled"

/**
 * Observes system high-text-contrast state for conversation theme suppression.
 * Listener lifetime matches the Koin single that owns this monitor.
 */
class HighTextContrastMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    private val _enabled = MutableStateFlow(readHighTextContrastEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val settingUri: Uri = Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST_SETTING)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val enabled = readHighTextContrastEnabled()
            if (_enabled.value != enabled) {
                _enabled.value = enabled
                Log.d(TAG, "high text contrast changed enabled=$enabled")
            }
        }
    }

    init {
        contentResolver.registerContentObserver(settingUri, false, observer)
        // Re-read after registration in case state changed during init.
        _enabled.value = readHighTextContrastEnabled()
        Log.d(TAG, "high text contrast monitor started enabled=${_enabled.value}")
    }

    private fun readHighTextContrastEnabled(): Boolean {
        return try {
            Settings.Secure.getInt(contentResolver, HIGH_TEXT_CONTRAST_SETTING, 0) == 1
        } catch (error: Throwable) {
            Log.w(TAG, "high text contrast read failed", error)
            false
        }
    }
}
