package me.ash.reader.ui.component.base

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * One deliberate vertical swipe, one immediate viewport jump. The 10% overlap keeps
 * reading context. No fling, smooth scrolling, overscroll stretch or card translation.
 * Callers disable the LazyColumn's drag handling while enabled; buttons, taps,
 * horizontal article actions and long-press retain their own gesture recognizers.
 */
fun Modifier.newspaperSwipeScroll(
    listState: LazyListState,
    enabled: Boolean = true,
    gestureKey: Any? = null,
    currentGestureKey: () -> Any? = { gestureKey },
    topObstructionPx: () -> Int = { 0 },
    onPreviousBoundary: () -> Unit = {},
    onNextBoundary: () -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val previousBoundary by rememberUpdatedState(onPreviousBoundary)
    val nextBoundary by rememberUpdatedState(onNextBoundary)
    val latestGestureKey by rememberUpdatedState(currentGestureKey)
    val currentTopObstruction by rememberUpdatedState(topObstructionPx)
    val thresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    if (!enabled) this else this
        .semantics {
            // Disabling inertial LazyColumn dragging must not disable accessibility scrolling.
            verticalScrollAxisRange = ScrollAxisRange(
                value = { listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset / 100000f },
                maxValue = {
                    if (listState.canScrollForward) listState.layoutInfo.totalItemsCount.toFloat()
                    else listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset / 100000f
                },
            )
            scrollBy { _, y ->
                val canMove = (y > 0f && listState.canScrollForward) ||
                    (y < 0f && listState.canScrollBackward)
                if (canMove) scope.launch { listState.scrollBy(y) }
                canMove
            }
        }
        .pointerInput(listState, thresholdPx, gestureKey) {
            var distance = 0f
            detectVerticalDragGestures(
                onDragStart = { distance = 0f },
                onDragCancel = { distance = 0f },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    distance += amount
                },
                onDragEnd = {
                    val forward = distance < -thresholdPx
                    val backward = distance > thresholdPx
                    distance = 0f
                    if ((forward || backward) && latestGestureKey() == gestureKey) {
                        // Read current bounds at dispatch, not a stale composition snapshot.
                        scope.launch {
                            // A key press/filter/resize may replace the visible slice while the
                            // finger is held or before this coroutine dispatches. Never act on it.
                            if (latestGestureKey() != gestureKey) return@launch
                            when {
                                forward && !listState.canScrollForward -> nextBoundary()
                                backward && !listState.canScrollBackward -> previousBoundary()
                                else -> {
                                    val layout = listState.layoutInfo
                                    val viewport = (layout.viewportEndOffset - layout.viewportStartOffset)
                                        .coerceAtLeast(1)
                                    // Pinned date headers cover content: retain overlap in the
                                    // unobscured viewport, including short landscape panes.
                                    val usable = (viewport - currentTopObstruction().coerceIn(0, viewport - 1))
                                        .coerceAtLeast(1)
                                    listState.scrollBy(usable * if (forward) 0.9f else -0.9f)
                                }
                            }
                        }
                    }
                },
            )
        }
}
