package me.ash.reader.ui.page.home.feeds

/** Immutable, display-only edition data. Every caption and count comes from a subscription. */
data class FolderPublication(
    val id: String,
    val name: String,
    val source: String = "",
    val iconUrl: String? = null,
    val count: Int = 0,
)

data class FolderSection(
    val id: String,
    val name: String,
    val publications: List<FolderPublication>,
) {
    val count: Int get() = publications.sumOf { it.count }
}

internal data class FolderNewspaperSlot(
    val section: FolderSection,
    val publication: FolderPublication? = null,
    val span: Int = 1,
    val featured: Boolean = false,
) {
    val key: String get() = publication?.let { "feed:${it.id}" } ?: "folder:${section.id}"
}

/** A row is indivisible. An expanded heading travels with its first publication row. */
internal data class FolderNewspaperRow(
    val slots: List<FolderNewspaperSlot>,
    val heading: FolderSection? = null,
    val continuation: Boolean = false,
) {
    val keys: List<String> get() = buildList {
        if (heading != null && !continuation) add("folder:${heading.id}")
        addAll(slots.map { it.key })
    }
    val key: String get() = keys.joinToString("|")
    val sectionIds: List<String> get() = slots.map { it.section.id }.distinct()
}

/** Editorial spans are determined by available readable lanes, never by fake content. */
internal fun folderNewspaperRows(
    sections: List<FolderSection>,
    expanded: Set<String>,
    columns: Int,
): List<FolderNewspaperRow> = buildList {
    val lanes = columns.coerceIn(1, 4)
    var pending = mutableListOf<FolderNewspaperSlot>()
    var used = 0
    var firstCollapsed = true
    fun flush() {
        if (pending.isNotEmpty()) add(FolderNewspaperRow(pending.toList()))
        pending = mutableListOf()
        used = 0
    }
    sections.forEach { section ->
        if (section.id !in expanded || section.publications.isEmpty()) {
            val featured = firstCollapsed
            val span = if (featured && lanes > 1) 2 else 1
            if (used + span > lanes) flush()
            pending += FolderNewspaperSlot(section, span = span, featured = featured)
            used += span
            firstCollapsed = false
            if (used == lanes) flush()
        } else {
            flush()
            var index = 0
            var firstRow = true
            while (index < section.publications.size) {
                val slots = mutableListOf<FolderNewspaperSlot>()
                var filled = 0
                while (index < section.publications.size && filled < lanes) {
                    val feature = firstRow && slots.isEmpty()
                    val span = if (feature && lanes >= 3) 2 else 1
                    slots += FolderNewspaperSlot(section, section.publications[index++], span, feature)
                    filled += span
                }
                add(FolderNewspaperRow(slots, heading = section, continuation = !firstRow))
                firstRow = false
            }
        }
    }
    flush()
}

/** Only whole-line previews change; type never scales below the user's accessibility setting. */
enum class FolderPreview { Full, Compact, Minimal, HeadlineOnly }

internal data class MeasuredFolderRow(
    val row: FolderNewspaperRow,
    val heightPx: Int,
    val preview: FolderPreview = FolderPreview.Full,
    /** Extra actual publication names in a collapsed section's directory digest. */
    val extraPublicationNames: Int = 0,
)

internal data class FolderNewspaperPage(val rows: List<MeasuredFolderRow>) {
    val keys: List<String> get() = rows.flatMap { it.row.keys }
    val heightPx: Int get() = rows.sumOf { it.heightPx }
}

/** Exact measured packing. Never rounds a viewport into a guessed number of subscriptions. */
internal fun packFolderNewspaperPages(
    rows: List<MeasuredFolderRow>,
    viewportHeightPx: Int,
): List<FolderNewspaperPage> {
    require(viewportHeightPx > 0)
    val pages = mutableListOf<FolderNewspaperPage>()
    var page = mutableListOf<MeasuredFolderRow>()
    var height = 0
    rows.forEach { row ->
        require(row.heightPx in 1..viewportHeightPx) { "A complete folder row must fit its viewport" }
        if (height + row.heightPx > viewportHeightPx) {
            pages += FolderNewspaperPage(page.toList())
            page = mutableListOf()
            height = 0
        }
        page += row
        height += row.heightPx
    }
    if (page.isNotEmpty()) pages += FolderNewspaperPage(page.toList())
    return pages
}

/**
 * Fit another complete row before leaving a large hole at the foot of the page. Compact rows
 * retain section titles, counts and actions, omitting optional digests and limiting publication
 * titles to two lines. The last row may trade preview detail for the next row, but we never
 * search combinations of a page.
 * Minimal/HeadlineOnly are reserved for genuinely short viewports, not density optimisation.
 *
 * Once membership is fixed, remaining space buys actual subscription names, not extra padding.
 * At most two compact candidates and a bounded binary search of detail counts are needed per
 * row. The caller caches native measurements, so page turns do not recompose these candidates.
 */
internal fun fillFolderNewspaperPages(
    rows: List<MeasuredFolderRow>,
    viewportHeightPx: Int,
    measure: (FolderNewspaperRow, FolderPreview, Int) -> MeasuredFolderRow,
): List<FolderNewspaperPage> {
    require(viewportHeightPx > 0)
    val pages = mutableListOf<FolderNewspaperPage>()
    var page = mutableListOf<MeasuredFolderRow>()
    var used = 0

    fun compact(row: MeasuredFolderRow): MeasuredFolderRow {
        if (row.preview != FolderPreview.Full) return row
        return measure(row.row, FolderPreview.Compact, 0).takeIf {
            it.heightPx in 1 until row.heightPx
        } ?: row
    }

    fun finishPage() {
        if (page.isEmpty()) return
        var spare = viewportHeightPx - used
        // Restore normal title/digest previews across the page before buying extra names in
        // an earlier row. Optional enrichment must never starve a later row's Full preview.
        val restored = page.map { current ->
            if (current.preview != FolderPreview.Compact) return@map current
            val budget = current.heightPx + spare
            val full = measure(current.row, FolderPreview.Full, 0)
            if (full.heightPx !in 1..budget) return@map current
            spare -= full.heightPx - current.heightPx
            full
        }
        val enriched = restored.map { current ->
            // Do not reverse an accessibility fallback chosen for a tiny viewport.
            if (current.preview != FolderPreview.Full) return@map current
            val budget = current.heightPx + spare
            var best = current
            val extraLimit = current.row.slots.maxOfOrNull { slot ->
                if (slot.publication != null) 0 else
                    (slot.section.publications.size - if (slot.featured) 3 else 2).coerceAtLeast(0)
            }?.coerceAtMost(32) ?: 0
            var low = 1
            var high = extraLimit
            while (low <= high) {
                val extra = low + (high - low) / 2
                val candidate = measure(current.row, FolderPreview.Full, extra)
                if (candidate.heightPx in 1..budget) {
                    best = candidate
                    low = extra + 1
                } else {
                    high = extra - 1
                }
            }
            spare -= best.heightPx - current.heightPx
            best
        }
        pages += FolderNewspaperPage(enriched)
        page = mutableListOf()
        used = 0
    }

    rows.forEach { row ->
        require(row.heightPx in 1..viewportHeightPx) { "A complete folder row must fit its viewport" }
        if (used + row.heightPx <= viewportHeightPx) {
            page += row
            used += row.heightPx
        } else {
            val smaller = compact(row)
            if (used + smaller.heightPx <= viewportHeightPx) {
                page += smaller
                used += smaller.heightPx
            } else {
                val last = page.lastOrNull()
                val smallerLast = last?.let(::compact)
                val reclaimed = if (last == null || smallerLast == null) 0
                    else last.heightPx - smallerLast.heightPx
                val next = listOf(row, smaller).firstOrNull {
                    used - reclaimed + it.heightPx <= viewportHeightPx
                }
                if (next != null && smallerLast != null) {
                    page[page.lastIndex] = smallerLast
                    page += next
                    used += next.heightPx - reclaimed
                } else {
                    finishPage()
                    page += row
                    used = row.heightPx
                }
            }
        }
    }
    finishPage()
    return pages
}

internal fun folderPageForAnchor(
    pages: List<FolderNewspaperPage>,
    anchor: String?,
    sectionId: String?,
    previousPage: Int,
): Int {
    val exact = anchor?.let { key -> pages.indexOfFirst { key in it.keys } } ?: -1
    if (exact >= 0) return exact
    // Collapsing a folder removes its publication keys. Keep the owning section, not page 1.
    val section = sectionId?.let { id -> pages.indexOfFirst { page ->
        page.rows.any { id in it.row.sectionIds }
    } } ?: -1
    return if (section >= 0) section else previousPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
}
