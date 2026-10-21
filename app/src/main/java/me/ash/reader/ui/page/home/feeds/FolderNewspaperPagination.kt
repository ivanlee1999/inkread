package me.ash.reader.ui.page.home.feeds

import me.ash.reader.ui.layout.OrderedPageChoice
import me.ash.reader.ui.layout.OrderedPageGroup
import me.ash.reader.ui.layout.orderedCompactPenalty
import me.ash.reader.ui.layout.planOrderedPages

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

/** Exact content equality with a bounded hash: a section can contain thousands of publications. */
internal data class FolderMeasureKey(
    val row: FolderNewspaperRow,
    val width: Int,
    val preview: FolderPreview,
    val extraNames: Int,
) {
    override fun hashCode(): Int {
        var result = width
        result = 31 * result + preview.hashCode()
        result = 31 * result + extraNames
        result = 31 * result + (row.heading?.id?.hashCode() ?: 0)
        result = 31 * result + row.continuation.hashCode()
        row.slots.forEach { slot ->
            result = 31 * result + slot.section.id.hashCode()
            result = 31 * result + (slot.publication?.id?.hashCode() ?: 0)
            result = 31 * result + slot.span
            result = 31 * result + slot.featured.hashCode()
        }
        return result
    }
}

/**
 * Composition-local and recreated for every content/typography identity by the viewport.
 * Natural row heights can survive height-only changes; only one exact page geometry is retained.
 * No Compose nodes, interaction state, or image resources are retained here.
 */
internal class FolderNewspaperLayoutCache {
    private val measurements = mutableMapOf<FolderMeasureKey, Int>()
    private var rows: List<FolderNewspaperRow>? = null
    private var width = -1
    private var height = -1
    private var pages: List<FolderNewspaperPage>? = null

    fun measure(row: FolderNewspaperRow, width: Int, preview: FolderPreview, extraNames: Int,
        measure: () -> Int): Int = measurements.getOrPut(FolderMeasureKey(row, width, preview, extraNames), measure)

    fun partition(rows: List<FolderNewspaperRow>, width: Int, height: Int): List<FolderNewspaperPage>? =
        if (this.rows === rows && this.width == width && this.height == height) pages else null

    fun retainPartition(rows: List<FolderNewspaperRow>, width: Int, height: Int,
        pages: List<FolderNewspaperPage>) {
        this.rows = rows
        this.width = width
        this.height = height
        this.pages = pages
    }
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
 * Compare bounded whole-page choices, not only the row that happened to hit the page edge.
 * Immutable ordered rows remain the indivisible units, including expanded section headings.
 * Compact retains section titles, counts and actions, omitting optional digests and limiting
 * publication titles to two lines. Minimal/HeadlineOnly are only tiny-viewport safety fallbacks.
 *
 * The shared planner balances measured empty space against the cost of reduced detail. Once
 * page membership is fixed, restore Full previews first, then buy actual subscription names
 * with the remaining space. At most one Compact probe and six detail probes are needed per
 * source row; the caller caches measurements across layout passes and page turns.
 */
internal fun fillFolderNewspaperPages(
    rows: List<MeasuredFolderRow>,
    viewportHeightPx: Int,
    measure: (FolderNewspaperRow, FolderPreview, Int) -> MeasuredFolderRow,
): List<FolderNewspaperPage> {
    require(viewportHeightPx > 0)
    rows.forEach { row ->
        require(row.heightPx in 1..viewportHeightPx) { "A complete folder row must fit its viewport" }
    }
    if (rows.isEmpty()) return emptyList()
    // A genuinely short edition already fits naturally. There is no reason to measure a
    // lower-detail alternative or turn a sparse last page into synthetic full-height boxes.
    val planned = if (rows.sumOf { it.heightPx.toLong() } <= viewportHeightPx) {
        listOf(rows)
    } else {
        val groups = rows.map { row ->
            val units = row.row.keys.size.coerceAtLeast(1)
            val choices = buildList {
                add(OrderedPageChoice(row, row.heightPx, units))
                if (row.preview == FolderPreview.Full) {
                    val compact = measure(row.row, FolderPreview.Compact, 0)
                    require(compact.row == row.row && compact.preview == FolderPreview.Compact)
                    if (compact.heightPx in 1 until row.heightPx) {
                        add(OrderedPageChoice(compact, compact.heightPx, units,
                            orderedCompactPenalty(row.heightPx, compact.heightPx)))
                    }
                }
            }
            OrderedPageGroup(choices)
        }
        planOrderedPages(groups, viewportHeightPx)
    }
    return planned.map { page ->
        var spare = viewportHeightPx - page.sumOf { it.heightPx }
        // Restore normal title/digest previews across the page before buying extra names in
        // an earlier row. Optional enrichment must never starve a later row's Full preview.
        val restored = page.map restore@ { current ->
            if (current.preview != FolderPreview.Compact) return@restore current
            val budget = current.heightPx + spare
            val full = measure(current.row, FolderPreview.Full, 0)
            require(full.row == current.row && full.preview == FolderPreview.Full)
            if (full.heightPx !in 1..budget) return@restore current
            spare -= full.heightPx - current.heightPx
            full
        }
        val enriched = restored.map enrich@ { current ->
            // Do not reverse an accessibility fallback chosen for a tiny viewport.
            if (current.preview != FolderPreview.Full) return@enrich current
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
                require(candidate.row == current.row && candidate.preview == FolderPreview.Full)
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
        FolderNewspaperPage(enriched)
    }
}

internal data class FolderReadingAnchor(val key: String, val sectionId: String?)

/** Keep the reading source even when reflow places earlier sources above it on the new page. */
internal fun folderReadingAnchor(
    page: FolderNewspaperPage?,
    preferred: FolderReadingAnchor? = null,
): FolderReadingAnchor? {
    val keys = page?.keys.orEmpty()
    if (keys.isEmpty()) return preferred
    val key = preferred?.key?.takeIf { it in keys }
        ?: preferred?.sectionId?.let { section ->
            "folder:$section".takeIf { it in keys }
                ?: page?.rows?.asSequence()?.flatMap { it.row.slots.asSequence() }
                    ?.firstOrNull { it.section.id == section }?.key
        }
        ?: keys.first()
    val section = page?.rows?.firstNotNullOfOrNull { measured ->
        val row = measured.row
        row.heading?.takeIf { !row.continuation && "folder:${it.id}" == key }?.id
            ?: row.slots.firstOrNull { it.key == key }?.section?.id
    }
    return FolderReadingAnchor(key, section)
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
