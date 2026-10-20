package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.lazy.LazyListState

/** Apply the slice and its position together on the next measure, without any animation. */
internal fun NewspaperPageChange.applyTo(
    listState: LazyListState,
    onPageChange: (Int) -> Unit,
) {
    onPageChange(page)
    listState.requestScrollToItem(rowIndex, scrollOffset)
}
