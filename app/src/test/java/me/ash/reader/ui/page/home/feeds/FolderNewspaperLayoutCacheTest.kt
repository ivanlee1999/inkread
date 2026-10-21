package me.ash.reader.ui.page.home.feeds

import org.junit.Assert.*
import org.junit.Test

class FolderNewspaperLayoutCacheTest {
    private fun section(feeds: Int = 3) = FolderSection("section", "Section", List(feeds) {
        FolderPublication("feed-$it", "Publication $it", "source-$it", "icon-$it", it)
    })

    private fun row(section: FolderSection) = folderNewspaperRows(listOf(section), emptySet(), 2).single()

    @Test fun measurementHashDoesNotTraverseAnExpandedSectionsPublicationList() {
        val source = section(2_000)
        val publications = object : AbstractList<FolderPublication>() {
            override val size = source.publications.size
            override fun get(index: Int) = source.publications[index]
            override fun hashCode(): Int = error("A measurement key cannot hash the whole section")
        }
        val expanded = source.copy(publications = publications)
        val rows = folderNewspaperRows(listOf(expanded), setOf(expanded.id), 2)
        val cache = FolderNewspaperLayoutCache()
        var measurements = 0
        repeat(2) {
            rows.forEach { row ->
                assertEquals(180, cache.measure(row, 800, FolderPreview.Full, 0) { measurements++; 180 })
            }
        }
        assertEquals(rows.size, measurements)
    }

    @Test fun boundedHashStillUsesExactTitleCountAndPublicationContentEquality() {
        val original = section()
        val changedSections = listOf(
            original.copy(name = "A new title"),
            original.copy(publications = original.publications.mapIndexed { index, publication ->
                if (index == 1) publication.copy(name = "A different publication") else publication
            }),
            original.copy(publications = original.publications.mapIndexed { index, publication ->
                if (index == 1) publication.copy(count = 101) else publication
            }),
            original.copy(publications = original.publications.mapIndexed { index, publication ->
                if (index == 1) publication.copy(source = "another source", iconUrl = "another icon") else publication
            }),
        )
        val key = FolderMeasureKey(row(original), 800, FolderPreview.Full, 0)
        val cache = FolderNewspaperLayoutCache()
        var calls = 0
        fun measured(section: FolderSection) = cache.measure(row(section), 800, FolderPreview.Full, 0) { ++calls }
        assertEquals(1, measured(original))
        assertEquals(1, measured(original.copy()))
        changedSections.forEachIndexed { index, section ->
            val changed = key.copy(row = row(section))
            assertEquals("Equal identity hashes are safe only with full content equality", key.hashCode(), changed.hashCode())
            assertNotEquals(key, changed)
            assertEquals(index + 2, measured(section))
        }
    }

    @Test fun widthPreviewAndEnrichmentEachKeepDistinctMeasurements() {
        val row = row(section())
        val cache = FolderNewspaperLayoutCache()
        var calls = 0
        fun measured(width: Int, preview: FolderPreview, extra: Int) =
            cache.measure(row, width, preview, extra) { ++calls }
        assertEquals(1, measured(800, FolderPreview.Full, 0))
        assertEquals(2, measured(801, FolderPreview.Full, 0))
        assertEquals(3, measured(800, FolderPreview.Compact, 0))
        assertEquals(4, measured(800, FolderPreview.Full, 1))
        assertEquals(1, measured(800, FolderPreview.Full, 0))
    }

    @Test fun pageTurnsReuseOneExactPlanAndGeometryOrRowsInvalidateIt() {
        val rows = folderNewspaperRows(listOf(section(40)), setOf("section"), 2)
        val cache = FolderNewspaperLayoutCache()
        var plans = 0
        fun plan(source: List<FolderNewspaperRow>, width: Int = 800, height: Int = 640): List<FolderNewspaperPage> =
            cache.partition(source, width, height) ?: fillFolderNewspaperPages(
                source.map { MeasuredFolderRow(it, 180) }, height,
            ) { row, preview, extra ->
                MeasuredFolderRow(row, (if (preview == FolderPreview.Full) 180 else 120) + extra * 24, preview, extra)
            }.also { plans++; cache.retainPartition(source, width, height, it) }
        val first = plan(rows)
        repeat(20) { assertSame(first, plan(rows)) }
        assertEquals(1, plans)
        assertEquals(rows.flatMap { it.keys }, first.flatMap { it.keys })
        assertNotSame(first, plan(rows, width = 801))
        assertEquals(2, plans)
        assertNotSame(first, plan(rows, height = 641))
        assertEquals(3, plans)
        assertNotSame(first, plan(rows.toList()))
        assertEquals(4, plans)
        // One current geometry only: returning to an older identity requires a new plan.
        assertNull(cache.partition(rows, 801, 640))
    }

    @Test fun cachedPlanRetainsExactAnchorLookupAndRecognizesEmptyPlans() {
        val rows = folderNewspaperRows(listOf(section(40)), setOf("section"), 2)
        val pages = packFolderNewspaperPages(rows.map { MeasuredFolderRow(it, 180) }, 640)
        val cache = FolderNewspaperLayoutCache()
        cache.retainPartition(rows, 800, 640, pages)
        val cached = requireNotNull(cache.partition(rows, 800, 640))
        val anchor = FolderReadingAnchor("feed:feed-30", "section")
        val target = folderPageForAnchor(cached, anchor.key, anchor.sectionId, 0)
        assertTrue(target > 0)
        assertEquals(anchor, folderReadingAnchor(cached[target], anchor))
        val empty = emptyList<FolderNewspaperRow>()
        cache.retainPartition(empty, 800, 640, emptyList())
        assertEquals(emptyList<FolderNewspaperPage>(), cache.partition(empty, 800, 640))
    }
}
