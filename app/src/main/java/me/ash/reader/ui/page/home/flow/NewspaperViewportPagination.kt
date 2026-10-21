package me.ash.reader.ui.page.home.flow

import me.ash.reader.ui.layout.OrderedPageChoice
import me.ash.reader.ui.layout.OrderedPageGroup
import me.ash.reader.ui.layout.orderedCompactPenalty
import me.ash.reader.ui.layout.planOrderedPages

/** Preview limits apply only to screen-paged editions; continuous articles remain unbounded. */
enum class NewspaperPreview { Full, Compact, Minimal, HeadlineOnly }

internal data class MeasuredNewspaperRow(
    val row: NewspaperRow,
    val heightPx: Int,
    val preview: NewspaperPreview = NewspaperPreview.Full,
    val extraDescriptionLines: Map<Int, Int> = emptyMap(),
    val extraHeadlineLines: Map<Int, Int> = emptyMap(),
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


/** Adapt measured rows into ordered keep-together groups for whole-page candidate scoring. */
internal fun packNewspaperViewportVariants(
    rowCount: Int,
    viewportHeightPx: Int,
    breakAfterSources: Set<Int> = emptySet(),
    variants: (Int) -> List<MeasuredNewspaperRow>,
): List<NewspaperViewportPage> {
    require(viewportHeightPx > 0)
    val groups = mutableListOf<OrderedPageGroup<List<MeasuredNewspaperRow>>>()
    val choicesBySources = mutableMapOf<List<Int>, List<MeasuredNewspaperRow>>()
    var heading: MeasuredNewspaperRow? = null
    repeat(rowCount) { index ->
        val choices = variants(index).also { candidates ->
            require(candidates.isNotEmpty())
            require(candidates.all { it.heightPx in 0..viewportHeightPx && it.row.indices == candidates.first().row.indices })
        }
        val preferred = choices.first()
        if (preferred.row.isDate) {
            heading = preferred
        } else {
            val pendingDate = heading
            choicesBySources[preferred.row.indices] = choices
            val candidates = choices.map { story ->
                val date = pendingDate?.takeIf { it.heightPx + story.heightPx <= viewportHeightPx }
                val rows = listOfNotNull(date, story)
                val full = choices.firstOrNull { it.preview == NewspaperPreview.Full && it.row == story.row }
                    ?: choices.firstOrNull { it.preview == NewspaperPreview.Full }
                val detailPenalty = if (story.preview == NewspaperPreview.Compact && full != null)
                    orderedCompactPenalty(full.heightPx, story.heightPx) else 0
                OrderedPageChoice(rows, rows.sumOf { it.heightPx }, story.row.indices.size, detailPenalty)
            }
            groups += OrderedPageGroup(candidates, preferred.row.indices.last() in breakAfterSources)
            heading = null
        }
    }
    return planOrderedPages(groups, viewportHeightPx).map { selected ->
        val rows = selected.flatten().toMutableList()
        var spare = viewportHeightPx - rows.sumOf { it.heightPx }
        // The bounded search normally already prefers these promotions. Guarantee richer
        // previews before excerpt enrichment even if a path was pruned from the finite beam.
        rows.indices.forEach { index ->
            val current = rows[index]
            if (current.preview == NewspaperPreview.Compact) {
                choicesBySources[current.row.indices]?.firstOrNull {
                    it.preview == NewspaperPreview.Full && it.row == current.row &&
                        it.heightPx <= current.heightPx + spare
                }?.let { full ->
                    spare -= full.heightPx - current.heightPx
                    rows[index] = full
                }
            }
        }
        NewspaperViewportPage(rows)
    }
}

/**
 * Restore Compact headlines toward their Full 4/5-line limits before buying excerpt lines.
 * Both stages use shorter-lane slack before page slack. Headlines need at most two whole-line
 * probes per story/pass; excerpts use two bounded searches over 0..24 extra lines. Measurement,
 * not character counts, fabricated text or forced heights, decides fit.
 */
internal fun enrichNewspaperViewportPage(
    page: NewspaperViewportPage,
    viewportHeightPx: Int,
    measure: (MeasuredNewspaperRow) -> MeasuredNewspaperRow,
): NewspaperViewportPage {
    require(page.heightPx <= viewportHeightPx)
    val rows = page.rows.toMutableList()
    fun restoreHeadline(rowIndex: Int, budget: Int) {
        var row = rows[rowIndex]
        if (row.row.isDate || row.preview != NewspaperPreview.Compact) return
        row.row.indices.forEach { source ->
            val current = row.extraHeadlineLines[source] ?: 0
            val fullLimit = if (row.row.isLead) 2 else 1
            for (extra in fullLimit downTo current + 1) {
                val candidate = measure(row.copy(extraHeadlineLines = row.extraHeadlineLines + (source to extra)))
                require(candidate.row == row.row && candidate.preview == row.preview)
                if (candidate.heightPx <= budget) { row = candidate; break }
            }
        }
        rows[rowIndex] = row
    }
    rows.indices.forEach { restoreHeadline(it, rows[it].heightPx) }
    rows.indices.forEach { index ->
        restoreHeadline(index, rows[index].heightPx + viewportHeightPx - rows.sumOf { it.heightPx })
    }
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
