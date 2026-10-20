package me.ash.reader.ui.page.home.flow

/** A page change includes the scroll position; changing the slice alone is never sufficient. */
internal data class NewspaperPageChange(
    val page: Int,
    val rowIndex: Int,
    val scrollOffset: Int = 0,
    val readRange: IntRange? = null,
)

/** Only an explicit forward turn is allowed to mark the slice being left as read. */
internal fun newspaperPageTurn(
    currentPage: Int,
    pageCount: Int,
    capacity: Int,
    forward: Boolean,
): NewspaperPageChange? {
    require(capacity > 0)
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val page = currentPage.coerceIn(0, lastPage)
    val target = (page + if (forward) 1 else -1).coerceIn(0, lastPage)
    if (target == page) return null
    return NewspaperPageChange(
        page = target,
        rowIndex = 0,
        scrollOffset = 0,
        readRange = if (forward) page * capacity until (page + 1) * capacity else null,
    )
}

/** Map an absolute source anchor before clamping a page number from a different capacity. */
internal fun newspaperPageForSource(
    sourceIndex: Int,
    itemCount: Int,
    capacity: Int,
    columns: Int,
    scrollOffset: Int = 0,
    isDate: (Int) -> Boolean,
): NewspaperPageChange {
    require(capacity > 0)
    val source = sourceIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    val page = source / capacity
    val start = page * capacity
    val rows = newspaperRows(itemCount, columns, start, start + capacity, isDate)
    return NewspaperPageChange(
        page = page,
        rowIndex = rows.rowForSourceIndex(source),
        scrollOffset = if (itemCount == 0) 0 else scrollOffset.coerceAtLeast(0),
    )
}

/** E-ink scrolling/remapping must never mark an unseen slice (or reader-return rows) read. */
internal fun newspaperScrolledReadRange(
    rows: List<NewspaperRow>,
    firstVisibleRowIndex: Int,
    sliceStart: Int,
    sliceEnd: Int,
    einkMode: Boolean = false,
): IntRange {
    if (einkMode) return IntRange.EMPTY
    val firstVisibleSource = rows.getOrNull(firstVisibleRowIndex)?.indices?.firstOrNull()
        ?: return IntRange.EMPTY
    val start = sliceStart.coerceAtLeast(0)
    val end = firstVisibleSource.coerceIn(start, sliceEnd.coerceAtLeast(start))
    return start until end
}

/** Capture concrete identities at the explicit turn, never defer source-index lookup. */
internal fun <T : Any, R : Any> newspaperPageReadItems(
    change: NewspaperPageChange,
    snapshot: List<T?>?,
    snapshotPager: Any?,
    activePager: Any,
    enabled: Boolean,
    article: (T) -> R?,
): List<R> {
    if (!enabled || snapshot == null || snapshotPager !== activePager) return emptyList()
    val range = change.readRange ?: return emptyList()
    return range.mapNotNull { index -> snapshot.getOrNull(index)?.let(article) }
}
