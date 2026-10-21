package me.ash.reader.ui.component.base

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
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** One completed single-finger vertical gesture, one screen turn. No scroll or fling. */
internal fun Modifier.eInkVerticalPageGesture(
    gestureKey: Any,
    currentGestureKey: () -> Any = { gestureKey },
    enabled: Boolean = true,
    onTurn: (forward: Boolean) -> Unit,
): Modifier = composed {
    val turn by rememberUpdatedState(onTurn)
    val currentKey by rememberUpdatedState(currentGestureKey)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val threshold = with(density) { 32.dp.toPx() }
    val leftEdge = WindowInsets.systemGestures.getLeft(density, direction)
    val rightEdge = WindowInsets.systemGestures.getRight(density, direction)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    this.onSizeChanged { viewport = it }
        .pointerInput(gestureKey, enabled, viewport, threshold, leftEdge, rightEdge) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x < leftEdge || down.position.x >= size.width - rightEdge) {
                    return@awaitEachGesture
                }
                var x = 0f
                var y = 0f
                var vertical = false
                var released = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        released = change.previousPressed
                        break
                    }
                    val delta = change.positionChange()
                    x += delta.x
                    y += delta.y
                    if (!vertical) {
                        if (abs(x) > viewConfiguration.touchSlop && abs(x) >= abs(y)) break
                        if (abs(y) > viewConfiguration.touchSlop && abs(y) > abs(x) * 1.2f) vertical = true
                    }
                    if (vertical) change.consume()
                }
                if (released && vertical && abs(y) > threshold && currentKey() == gestureKey) {
                    turn(y < 0f)
                }
            }
        }
}
