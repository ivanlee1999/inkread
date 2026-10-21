package me.ash.reader.ui.page.home.feeds

import org.junit.Assert.*
import org.junit.Test

class FolderNewspaperPaginationTest {
    private fun sections(count: Int = 12, feeds: Int = 7) = (0 until count).map { group ->
        FolderSection("g$group", "Section $group", (0 until feeds).map { feed ->
            FolderPublication("g${group}f$feed", "Publication $feed", count = group + feed)
        })
    }

    @Test fun collapsedSectionsAreUniqueWithPhoneAndTabletSpans() {
        val data = sections()
        (1..4).forEach { columns ->
            val rows = folderNewspaperRows(data, emptySet(), columns)
            assertEquals(data.map { "folder:${it.id}" }, rows.flatMap { it.keys })
            assertTrue(rows.all { row -> row.slots.sumOf { it.span } <= columns })
            assertEquals(if (columns > 1) 2 else 1, rows.first().slots.first().span)
        }
    }

    @Test fun expandedSectionsKeepEveryPublicationInSourceOrder() {
        val data = sections()
        val expanded = setOf("g1", "g4", "g8")
        (1..4).forEach { columns ->
            val rows = folderNewspaperRows(data, expanded, columns)
            val expected = data.flatMap { section ->
                listOf("folder:${section.id}") + if (section.id in expanded)
                    section.publications.map { "feed:${it.id}" } else emptyList()
            }
            assertEquals(expected, rows.flatMap { it.keys })
            assertTrue(rows.filter { it.heading != null && !it.continuation }.all { it.slots.isNotEmpty() })
        }
    }

    @Test fun exactBoundaryStaysOnPageAndOnePixelOverflowStartsNext() {
        val rows = folderNewspaperRows(sections(3), emptySet(), 1)
        val measured = rows.mapIndexed { index, row -> MeasuredFolderRow(row, if (index == 2) 101 else 100) }
        val pages = packFolderNewspaperPages(measured, 200)
        assertEquals(listOf(200, 101), pages.map { it.heightPx })
        assertEquals(measured, pages.flatMap { it.rows })
    }

    @Test fun variableNativeMeasurementsProduceCompleteMultipagePartitions() {
        val rows = folderNewspaperRows(sections(18), setOf("g2", "g6", "g12"), 3)
        val measured = rows.mapIndexed { index, row -> MeasuredFolderRow(row, 83 + (index % 4) * 71) }
        listOf(320, 444, 720).forEach { height ->
            val pages = packFolderNewspaperPages(measured, height)
            assertTrue(pages.size > 1)
            assertTrue(pages.all { it.heightPx <= height && it.rows.isNotEmpty() })
            assertEquals(rows.flatMap { it.keys }, pages.flatMap { it.keys })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedPreviewMustBeRecomposedNotSilentlyClipped() {
        val row = folderNewspaperRows(sections(1), emptySet(), 1).single()
        packFolderNewspaperPages(listOf(MeasuredFolderRow(row, 201)), 200)
    }

    @Test fun changingColumnsRestoresExactSourceAnchorNotOldPageNumber() {
        val data = sections(20)
        val rows = folderNewspaperRows(data, emptySet(), 3)
        val pages = packFolderNewspaperPages(rows.map { MeasuredFolderRow(it, 120) }, 360)
        val page = folderPageForAnchor(pages, "folder:g15", "g15", 0)
        assertTrue("folder:g15" in pages[page].keys)
    }

    @Test fun collapsingPublicationFallsBackToItsOwningFolder() {
        val data = sections(20)
        val collapsed = folderNewspaperRows(data, emptySet(), 2)
        val pages = packFolderNewspaperPages(collapsed.map { MeasuredFolderRow(it, 160) }, 320)
        val page = folderPageForAnchor(pages, "feed:g14f3", "g14", 0)
        assertTrue(page > 0)
        assertTrue("folder:g14" in pages[page].keys)
    }

    @Test fun emptyFoldersAndEmptyEditionsStayValid() {
        val data = sections(4, 0)
        val rows = folderNewspaperRows(data, data.map { it.id }.toSet(), 2)
        assertEquals(data.map { "folder:${it.id}" }, rows.flatMap { it.keys })
        assertEquals(emptyList<FolderNewspaperPage>(), packFolderNewspaperPages(emptyList(), 400))
        assertEquals(0, folderPageForAnchor(emptyList(), "removed", "removed", 7))
    }

    @Test fun countIsRealPublicationAggregateIncludingZero() {
        val section = FolderSection("a", "Actual", listOf(
            FolderPublication("one", "One", count = 0),
            FolderPublication("two", "Two", count = 19),
            FolderPublication("three", "Three", count = 4),
        ))
        assertEquals(23, section.count)
        assertEquals(0, section.copy(publications = emptyList()).count)
    }
}
