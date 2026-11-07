package vip.mystery0.pixel.text.ui.component

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import vip.mystery0.pixel.text.BuildConfig

/** 仅 Debug 记录页面首次内容就绪到下一帧的耗时，不记录短信或搜索词。 */
@Composable
internal fun ListLoadTrace(page: String, count: Int?, requestKey: Any? = Unit) {
    if (!BuildConfig.DEBUG) return
    val trace = remember(page, requestKey) { ListLoadTraceState(SystemClock.elapsedRealtime()) }
    LaunchedEffect(trace, count != null) {
        if (count == null || trace.reported) return@LaunchedEffect
        trace.reported = true
        withFrameNanos { }
        Log.d("ListLoad", "list content ready page=$page count=$count elapsed_ms=${SystemClock.elapsedRealtime() - trace.started}")
    }
}

private class ListLoadTraceState(val started: Long, var reported: Boolean = false)
