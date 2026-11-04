package vip.mystery0.pixel.text.worker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

/** 启动或重新开启识别时补排；扫描器会跳过身份和代次仍有效的缓存。 */
class SpamModelRefreshObserver(context: Context, settings: AppSettingsRepository) {
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings.map { it.spamDetectionEnabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) HistoricalSpamScanWorker.schedule(context.applicationContext, replace = false, silent = true)
            }
        }
    }
}
