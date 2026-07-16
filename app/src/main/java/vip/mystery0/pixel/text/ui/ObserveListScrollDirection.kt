package vip.mystery0.pixel.text.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.sign

/**
 * 累计列表的同方向微小滚动，达到阈值后再通知调用方。
 */
@Composable
fun ObserveListScrollDirection(
    listState: LazyListState,
    threshold: Int = DEFAULT_SCROLL_DIRECTION_THRESHOLD,
    onScrollDirectionChanged: (isScrollingDown: Boolean) -> Unit,
) {
    val currentOnScrollDirectionChanged by rememberUpdatedState(onScrollDirectionChanged)
    LaunchedEffect(listState, threshold) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        var accumulatedDelta = 0

        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                val indexDelta = index - previousIndex
                val delta = if (indexDelta != 0) indexDelta.sign * threshold else offset - previousOffset

                if (delta != 0) {
                    if (accumulatedDelta != 0 && delta.sign != accumulatedDelta.sign) {
                        accumulatedDelta = 0
                    }
                    accumulatedDelta += delta
                    if (abs(accumulatedDelta) >= threshold) {
                        currentOnScrollDirectionChanged(accumulatedDelta > 0)
                        accumulatedDelta = 0
                    }
                }

                previousIndex = index
                previousOffset = offset
            }
    }
}

private const val DEFAULT_SCROLL_DIRECTION_THRESHOLD = 4
