package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority

/** Apply the slice and its position together on the next measure, without any animation. */
internal fun NewspaperPageChange.applyTo(
    listState: LazyListState,
    onPageChange: (Int) -> Unit,
) {
    onPageChange(page)
    listState.requestScrollToItem(rowIndex, scrollOffset)
}

/** Shared button/key action: capture and mark identities before navigation, without suspension. */
internal fun <T : Any, R : Any> turnNewspaperPage(
    currentPage: Int,
    pageCount: Int,
    capacity: Int,
    forward: Boolean,
    listState: LazyListState,
    snapshot: List<T?>?,
    snapshotPager: Any?,
    activePager: Any,
    markReadEnabled: Boolean,
    article: (T) -> R?,
    onMarkRead: (List<R>) -> Unit,
    onPageChange: (Int) -> Unit,
): Boolean {
    val change = newspaperPageTurn(currentPage, pageCount, capacity, forward) ?: return false
    val articles = newspaperPageReadItems(
        change, snapshot, snapshotPager, activePager, markReadEnabled, article,
    )
    if (articles.isNotEmpty()) onMarkRead(articles)
    change.applyTo(listState, onPageChange)
    return true
}

/** The list's low-priority hardware-key consumer follows the current list and action callback. */
@Composable
internal fun EInkVolumePageKeys(
    enabled: Boolean,
    listState: LazyListState,
    onTurn: (Boolean) -> Boolean,
    onTurned: () -> Unit = {},
) {
    val currentOnTurn by rememberUpdatedState(onTurn)
    val currentOnTurned by rememberUpdatedState(onTurned)
    if (enabled) {
        val owner = remember(listState) { Any() }
        val volumeKeyFlow = remember(owner) { VolumeKeyEventBus.events(VolumeKeyPriority.LOW, owner) }
        DisposableEffect(owner) {
            VolumeKeyEventBus.register(VolumeKeyPriority.LOW, owner)
            onDispose { VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW, owner) }
        }
        LaunchedEffect(volumeKeyFlow, listState) {
            volumeKeyFlow.collect { event ->
                if (VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW, owner) &&
                    currentOnTurn(event == VolumeKeyEvent.NEXT)
                ) {
                    currentOnTurned()
                }
            }
        }
    }
}

/** Reset only for a changed feed/filter stream, not initial composition or reader return. */
@Composable
internal fun EInkPagerReset(
    pagerIdentity: Any,
    enabled: Boolean,
    listState: LazyListState,
    onPageChange: (Int) -> Unit,
) {
    var previousPagerIdentity by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(pagerIdentity) {
        if (previousPagerIdentity != null && previousPagerIdentity !== pagerIdentity && enabled) {
            NewspaperPageChange(0, rowIndex = 0).applyTo(listState, onPageChange)
        }
        previousPagerIdentity = pagerIdentity
    }
}

/** Preserve the live source anchor before adopting a newly measured layout or clamping. */
@Composable
internal fun EInkMeasuredPageLayout(
    enabled: Boolean,
    targetCapacity: Int,
    targetColumns: Int,
    hasMeasuredPane: Boolean,
    itemCount: Int,
    isRefreshComplete: Boolean,
    listState: LazyListState,
    currentPage: () -> Int,
    currentCapacity: () -> Int,
    currentColumns: () -> Int,
    isDate: (Int) -> Boolean,
    onLayoutChange: (Int, Int) -> Unit,
    onPageChange: (Int) -> Unit,
) {
    LaunchedEffect(
        enabled, targetCapacity, targetColumns, hasMeasuredPane, itemCount, isRefreshComplete,
    ) {
        // Do not reinterpret restored state before measurement/data are ready.
        if (!enabled || !hasMeasuredPane || (itemCount == 0 && !isRefreshComplete)) {
            return@LaunchedEffect
        }
        val oldCapacity = currentCapacity()
        val oldColumns = currentColumns()
        val page = currentPage()
        if (oldCapacity != targetCapacity || oldColumns != targetColumns) {
            // Another effect may have requested a reader/filter page in this same frame.
            // Live getters avoid remapping a composition-captured, already obsolete page.
            val previousStart = page * oldCapacity
            val previousRows = newspaperRows(
                itemCount, oldColumns, previousStart, previousStart + oldCapacity, isDate,
            )
            val sourceAnchor = previousRows.getOrNull(listState.firstVisibleItemIndex)
                ?.indices?.firstOrNull() ?: previousStart
            val change = newspaperPageForSource(
                sourceAnchor, itemCount, targetCapacity, targetColumns,
                // A shorter resized row could be skipped if its old pixel offset were reused.
                scrollOffset = 0, isDate = isDate,
            )
            onLayoutChange(targetCapacity, targetColumns)
            change.applyTo(listState, onPageChange)
        } else {
            val pageCount = maxOf(1, (itemCount + oldCapacity - 1) / oldCapacity)
            if (page >= pageCount) {
                // Never clamp using a new capacity before mapping the previous source anchor.
                NewspaperPageChange(pageCount - 1, rowIndex = 0).applyTo(listState, onPageChange)
            }
        }
    }
}
