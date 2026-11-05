package vip.mystery0.pixel.text.worker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

/** 启动或重新开启识别时补排；扫描器按已存模型版本跳过已处理消息。 */
@OptIn(FlowPreview::class)
class SpamModelRefreshObserver(context: Context, settings: AppSettingsRepository, database: MessageMirrorDatabase) {
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings.map { it.spamDetectionEnabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) HistoricalSpamScanWorker.schedule(context.applicationContext, replace = false, silent = true)
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            database.syncDao().observeStates()
                .map { states -> states.firstOrNull { it.collection == "spam_content_change" && !it.complete }?.generation }
                .distinctUntilChanged()
                .debounce(500)
                .filterNotNull()
                .collect {
                    // 关闭模型检测仍要更新关键词与白名单；独立串行任务不取消用户扫描。
                    HistoricalSpamScanWorker.scheduleContentRefresh(context.applicationContext)
                }
        }
    }
}
