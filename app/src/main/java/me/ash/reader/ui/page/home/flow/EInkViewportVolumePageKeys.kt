package me.ash.reader.ui.page.home.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority

/** Screen pages have no LazyListState or hidden within-page scroll position. */
@Composable
internal fun EInkVolumePageKeys(
    enabled: Boolean,
    state: NewspaperViewportState,
    onTurn: (Boolean) -> Boolean,
    onTurned: () -> Unit = {},
) {
    val turn by rememberUpdatedState(onTurn)
    val turned by rememberUpdatedState(onTurned)
    if (enabled) {
        val owner = remember(state, state.revision) { Any() }
        val events = remember(owner) { VolumeKeyEventBus.events(VolumeKeyPriority.LOW, owner) }
        DisposableEffect(owner) {
            VolumeKeyEventBus.register(VolumeKeyPriority.LOW, owner)
            onDispose { VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW, owner) }
        }
        LaunchedEffect(events, state) {
            events.collect { event ->
                if (VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW, owner) &&
                    turn(event == VolumeKeyEvent.NEXT)) turned()
            }
        }
    }
}
