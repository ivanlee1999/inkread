package me.ash.reader.ui.page.home.flow

/** Preview limits apply only to screen-paged editions; continuous articles remain unbounded. */
enum class NewspaperPreview { Full, Compact, Minimal, HeadlineOnly }

internal data class MeasuredNewspaperRow(
    val row: NewspaperRow,
    val heightPx: Int,
    val preview: NewspaperPreview = NewspaperPreview.Full,
)

internal data class NewspaperViewportPage(val rows: List<MeasuredNewspaperRow>) {
    val indices: List<Int> get() = rows.flatMap { it.row.indices }
    val heightPx: Int get() = rows.sumOf { it.heightPx }
}

/**
 * Pack actual measured production blocks, never an estimated article capacity. A date heading
 * travels with the following story; on an exceptionally short screen the redundant date heading
 * is omitted rather than displacing or clipping that story. Every article occurs exactly once.
 */
internal fun packNewspaperViewportPages(
    rows: List<MeasuredNewspaperRow>,
    viewportHeightPx: Int,
    breakAfterSources: Set<Int> = emptySet(),
): List<NewspaperViewportPage> {
    require(viewportHeightPx > 0)
    val pages = mutableListOf<NewspaperViewportPage>()
    var page = mutableListOf<MeasuredNewspaperRow>()
    var height = 0
    var heading: MeasuredNewspaperRow? = null
    fun flush() {
        if (page.isNotEmpty()) pages += NewspaperViewportPage(page.toList())
        page = mutableListOf()
        height = 0
    }
    rows.forEach { measured ->
        require(measured.heightPx in 0..viewportHeightPx) { "Preview must fit its measured viewport" }
        if (measured.row.isDate) {
            heading = measured
        } else {
            val date = heading?.takeIf { it.heightPx + measured.heightPx <= viewportHeightPx }
            val nextHeight = measured.heightPx + (date?.heightPx ?: 0)
            if (height + nextHeight > viewportHeightPx) flush()
            if (date != null) { page += date; height += date.heightPx }
            page += measured
            height += measured.heightPx
            heading = null
            if (measured.row.indices.last() in breakAfterSources) flush()
        }
    }
    flush()
    return pages
}
