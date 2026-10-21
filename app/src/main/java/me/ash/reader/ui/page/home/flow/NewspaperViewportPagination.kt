package me.ash.reader.ui.page.home.flow

/** Preview limits apply only to screen-paged editions; continuous articles remain unbounded. */
enum class NewspaperPreview { Full, Compact, Minimal, HeadlineOnly }

internal data class MeasuredNewspaperRow(
    val row: NewspaperRow,
    val heightPx: Int,
    val preview: NewspaperPreview = NewspaperPreview.Full,
    val extraDescriptionLines: Map<Int, Int> = emptyMap(),
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


/**
 * Every choice describes the same complete source row. Full and Compact are the only density
 * choices; Minimal/HeadlineOnly remain safety fallbacks for a row that cannot fit a whole pane.
 * At most the preceding story row is reconsidered, so packing is linear rather than a global
 * combinatorial search. A locked source end is flushed before any later row is considered.
 */
internal fun packNewspaperViewportVariants(
    rowCount: Int,
    viewportHeightPx: Int,
    breakAfterSources: Set<Int> = emptySet(),
    variants: (Int) -> List<MeasuredNewspaperRow>,
): List<NewspaperViewportPage> {
    require(viewportHeightPx > 0)
    val pages = mutableListOf<NewspaperViewportPage>()
    var page = mutableListOf<MeasuredNewspaperRow>()
    var pageOptions = mutableListOf<List<MeasuredNewspaperRow>>()
    var height = 0
    var heading: MeasuredNewspaperRow? = null
    fun flush() {
        // Compact is a means of admitting another whole story, never an end in itself.
        page.indices.forEach { index ->
            if (page[index].preview == NewspaperPreview.Compact) {
                pageOptions[index].firstOrNull { it.preview == NewspaperPreview.Full &&
                    height - page[index].heightPx + it.heightPx <= viewportHeightPx }?.let {
                    height += it.heightPx - page[index].heightPx
                    page[index] = it
                }
            }
        }
        if (page.isNotEmpty()) pages += NewspaperViewportPage(page.toList())
        page = mutableListOf(); pageOptions = mutableListOf(); height = 0
    }
    repeat(rowCount) { index ->
        val choices = variants(index).also { candidates ->
            require(candidates.isNotEmpty())
            require(candidates.all { it.heightPx in 0..viewportHeightPx && it.row.indices == candidates.first().row.indices })
        }
        val preferred = choices.first()
        if (preferred.row.isDate) {
            heading = preferred
        } else {
            fun dateFor(story: MeasuredNewspaperRow) = heading?.takeIf { it.heightPx + story.heightPx <= viewportHeightPx }
            fun additionHeight(story: MeasuredNewspaperRow) = story.heightPx + (dateFor(story)?.heightPx ?: 0)
            var selected = choices.firstOrNull { height + additionHeight(it) <= viewportHeightPx }
            if (selected == null && page.isNotEmpty()) {
                // Compact one preceding row only when it admits the next real complete story row.
                val previousIndex = page.indexOfLast { !it.row.isDate }
                if (previousIndex >= 0) {
                    val old = page[previousIndex]
                    val alternatives = pageOptions[previousIndex].filter {
                        it.preview == NewspaperPreview.Full || it.preview == NewspaperPreview.Compact
                    }
                    for (candidate in choices) {
                        val replacement = alternatives.firstOrNull {
                            height - old.heightPx + it.heightPx + additionHeight(candidate) <= viewportHeightPx
                        }
                        if (replacement != null) {
                            page[previousIndex] = replacement
                            height += replacement.heightPx - old.heightPx
                            selected = candidate
                            break
                        }
                    }
                }
            }
            if (selected == null) { flush(); selected = preferred }
            val story = selected!!
            dateFor(story)?.let { page += it; pageOptions += listOf(it); height += it.heightPx }
            page += story; pageOptions += choices; height += story.heightPx
            heading = null
            if (story.row.indices.last() in breakAfterSources) flush()
        }
    }
    flush()
    return pages
}

/**
 * Spend free space on actual saved excerpt lines, first in shorter lanes without growing rows,
 * then in the remainder of the page. Each story has at most two bounded binary searches over
 * 0..24 extra whole lines. Measurement, not character counts or forced heights, decides fit.
 */
internal fun enrichNewspaperViewportPage(
    page: NewspaperViewportPage,
    viewportHeightPx: Int,
    measure: (MeasuredNewspaperRow) -> MeasuredNewspaperRow,
): NewspaperViewportPage {
    require(page.heightPx <= viewportHeightPx)
    val rows = page.rows.toMutableList()
    fun grow(rowIndex: Int, budget: Int) {
        var row = rows[rowIndex]
        if (row.row.isDate || row.preview == NewspaperPreview.Minimal || row.preview == NewspaperPreview.HeadlineOnly) return
        row.row.indices.forEach { source ->
            var low = row.extraDescriptionLines[source] ?: 0
            if (low == 24) return@forEach
            // Short saved excerpts and roomy lanes finish with a single measurement.
            val maximum = measure(row.copy(extraDescriptionLines = row.extraDescriptionLines + (source to 24)))
            require(maximum.row == row.row && maximum.preview == row.preview)
            if (maximum.heightPx <= budget) {
                row = maximum
                return@forEach
            }
            var high = 23
            while (low < high) {
                val mid = (low + high + 1) / 2
                val candidate = measure(row.copy(extraDescriptionLines = row.extraDescriptionLines + (source to mid)))
                require(candidate.row == row.row && candidate.preview == row.preview)
                if (candidate.heightPx <= budget) {
                    low = mid
                    row = candidate
                } else high = mid - 1
            }
        }
        rows[rowIndex] = row
    }
    rows.indices.forEach { grow(it, rows[it].heightPx) }
    rows.indices.forEach { index ->
        grow(index, rows[index].heightPx + viewportHeightPx - rows.sumOf { it.heightPx })
    }
    return NewspaperViewportPage(rows)
}
