package me.ash.reader.ui.page.home.flow

import androidx.compose.runtime.Composable
import me.ash.reader.ui.component.base.EInkListVolumePageKeys

/** Screen pages have no LazyListState or hidden within-page scroll position. */
@Composable
internal fun EInkVolumePageKeys(
    enabled: Boolean,
    state: NewspaperViewportState,
    onTurn: (Boolean) -> Boolean,
    onTurned: () -> Unit = {},
) {
    EInkListVolumePageKeys(
        enabled = enabled && state.pages.isNotEmpty(),
        ownerKey = state to state.revision,
        onTurn = onTurn,
        onTurned = onTurned,
    )
}
