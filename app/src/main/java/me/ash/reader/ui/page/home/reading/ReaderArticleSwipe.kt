package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Horizontal article shortcuts never outlive their article, page, viewport, or pointer. */
internal fun Modifier.readerArticleSwipe(
    gestureKey: Any,
    enabled: Boolean,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    thresholdDp: Dp = 56.dp,
    isGestureEnabled: () -> Boolean = { true },
    consumeRejectedVertical: Boolean = false,
): Modifier = composed {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val latestKey by rememberUpdatedState(gestureKey)
    val canSwipe by rememberUpdatedState(isGestureEnabled)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val threshold = with(density) { thresholdDp.toPx() }
    val leftEdge = WindowInsets.systemGestures.getLeft(density, direction)
    val rightEdge = WindowInsets.systemGestures.getRight(density, direction)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    this.onSizeChanged { viewport = it }
        .pointerInput(gestureKey, enabled, viewport, threshold, leftEdge, rightEdge, consumeRejectedVertical) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                // Child tap zones consume DOWN normally. They must still receive taps.
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!canSwipe() || down.position.x < leftEdge || down.position.x >= size.width - rightEdge) {
                    return@awaitEachGesture
                }
                var x = 0f
                var y = 0f
                var horizontal = false
                var released = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) {
                        // Cancel child tap recognition as well as our swipe. Merely leaving
                        // the recognizer lets a wide card open on the first pointer's UP.
                        var rejectedEvent = event
                        while (true) {
                            rejectedEvent.changes.forEach { it.consume() }
                            if (rejectedEvent.changes.none { it.pressed }) break
                            rejectedEvent = awaitPointerEvent()
                        }
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // A child/parent or the system claiming a gesture cancels navigation.
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        released = change.previousPressed
                        break
                    }
                    val delta = change.positionChange()
                    x += delta.x
                    y += delta.y
                    if (!horizontal) {
                        if (abs(y) > viewConfiguration.touchSlop && abs(y) >= abs(x)) {
                            if (consumeRejectedVertical) {
                                // The paginated reader has tap zones, not a vertical scroll
                                // owner. Cancel those taps once this is a drag. List callers
                                // leave this off so their vertical pager/scroll can claim it.
                                var rejectedEvent = event
                                while (true) {
                                    rejectedEvent.changes.forEach { it.consume() }
                                    if (rejectedEvent.changes.none { it.pressed }) break
                                    rejectedEvent = awaitPointerEvent()
                                }
                            }
                            break
                        }
                        if (abs(x) > viewConfiguration.touchSlop && abs(x) > abs(y) * 1.2f) {
                            horizontal = true
                        }
                    }
                    if (horizontal) change.consume()
                }
                if (released && horizontal && latestKey == gestureKey && canSwipe()) {
                    if (x < -threshold) next?.invoke()
                    else if (x > threshold) previous?.invoke()
                }
            }
        }
}
