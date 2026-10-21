package me.ash.reader.ui.component.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority

/** Only the resumed, focused list owns keys; readers retain their higher-priority reservation. */
@Composable
internal fun EInkListVolumePageKeys(
    enabled: Boolean,
    ownerKey: Any,
    onTurn: (Boolean) -> Boolean,
    onTurned: () -> Unit = {},
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    val focused = window.isWindowFocused
    val owner = remember(ownerKey) { Any() }
    val active by rememberUpdatedState(enabled)
    val turn by rememberUpdatedState(onTurn)
    val turned by rememberUpdatedState(onTurned)
    LaunchedEffect(enabled, focused, lifecycle, owner) {
        if (!enabled || !focused) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val events = VolumeKeyEventBus.register(VolumeKeyPriority.LOW, owner)
            try {
                events.collect { event ->
                    if (active && window.isWindowFocused &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW, owner) &&
                        turn(event == VolumeKeyEvent.NEXT)
                    ) turned()
                }
            } finally {
                VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW, owner)
            }
        }
    }
}
