package me.ash.reader.ui.page.home.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewspaperPaginationTest {
    @Test fun capacityIncreaseMapsSourceBeforeClampingOldPage() {
        // Regression: clamping page 9 to page 5 before remapping used to yield source 15.
        val oldRows = newspaperRows(30, 1, start = 9 * 3, end = 10 * 3) { false }
        val sourceAnchor = oldRows[0].indices.first()
        val change = newspaperPageForSource(sourceAnchor, 30, capacity = 5, columns = 1) { false }
        assertEquals(27, sourceAnchor)
        assertEquals(5, change.page)
        assertEquals(2, change.rowIndex)
        assertNull(change.readRange)
        val newRows = newspaperRows(30, 1, change.page * 5, (change.page + 1) * 5) { false }
        assertEquals(listOf(27), newRows[change.rowIndex].indices)
    }

    @Test fun resizePreservesScrolledSourceNotJustPageStartAndResetsOffset() {
        val oldRows = newspaperRows(30, 1, start = 12, end = 18) { it == 14 }
        val sourceAnchor = oldRows[4].indices.first()
        // Do not reuse the old pixel offset: a shorter resized row could scroll past the anchor.
        val change = newspaperPageForSource(sourceAnchor, 30, 5, 2) { it == 14 }
        assertEquals(16, sourceAnchor)
        assertEquals(3, change.page)
        assertEquals(1, change.rowIndex)
        assertEquals(0, change.scrollOffset)
        val newRows = newspaperRows(30, 2, change.page * 5, (change.page + 1) * 5) { it == 14 }
        assertTrue(sourceAnchor in newRows[change.rowIndex].indices)
        assertNull(change.readRange)
    }

    @Test fun capacityDecreaseAndDateHeadersPreserveAbsoluteSource() {
        val change = newspaperPageForSource(27, 30, 3, 2) { it == 27 }
        assertEquals(9, change.page)
        assertEquals(0, change.rowIndex)
        val rows = newspaperRows(30, 2, 27, 30) { it == 27 }
        assertEquals(listOf(27), rows[change.rowIndex].indices)
        assertTrue(rows[change.rowIndex].isDate)
        assertNull(change.readRange)
    }

    @Test fun forwardTurnResetsRowAndOffsetAndMarksOnlyTheSliceBeingLeft() {
        val change = newspaperPageTurn(currentPage = 2, pageCount = 5, capacity = 6, forward = true)!!
        assertEquals(3, change.page)
        assertEquals(0, change.rowIndex)
        assertEquals(0, change.scrollOffset)
        assertEquals((12 until 18).toList(), change.readRange!!.toList())
        assertTrue(change.readRange.none { it < 12 || it >= 18 })
    }

    @Test fun backwardTurnResetsRowAndOffsetWithoutMarkingAnythingRead() {
        val change = newspaperPageTurn(currentPage = 3, pageCount = 5, capacity = 6, forward = false)!!
        assertEquals(NewspaperPageChange(page = 2, rowIndex = 0, scrollOffset = 0), change)
        assertNull(change.readRange)
    }

    @Test fun boundaryTurnsDoNothingAndDoNotEnqueueReadRanges() {
        assertNull(newspaperPageTurn(0, 5, 6, forward = false))
        assertNull(newspaperPageTurn(4, 5, 6, forward = true))
        assertNull(newspaperPageTurn(0, 0, 6, forward = true))
        assertNull(newspaperPageTurn(0, 1, 6, forward = false))
    }

    @Test fun einkScrollAndReaderReturnNeverMarkEarlierPagesOrRowsRead() {
        val rows = newspaperRows(30, 2, start = 12, end = 18) { it == 14 }
        for (visibleRow in rows.indices) {
            assertTrue(newspaperScrolledReadRange(rows, visibleRow, 12, 18, einkMode = true).isEmpty())
        }
        val readerReturn = newspaperPageForSource(17, 30, 6, 2) { it == 14 }
        assertTrue(newspaperScrolledReadRange(rows, readerReturn.rowIndex, 12, 18, einkMode = true).isEmpty())
        assertNull(readerReturn.readRange)
    }

    @Test fun regularScrollRetainsExistingBehaviorWithTwoColumnRowMapping() {
        val rows = newspaperRows(10, 2) { it == 0 || it == 5 }
        assertEquals(listOf(2, 3), rows[2].indices)
        assertEquals(listOf(0, 1), newspaperScrolledReadRange(rows, 2, 0, 10).toList())
        assertEquals((0 until 6).toList(), newspaperScrolledReadRange(rows, 5, 0, 10).toList())
        assertTrue(newspaperScrolledReadRange(rows, -1, 0, 10).isEmpty())
        assertTrue(newspaperScrolledReadRange(rows, 99, 0, 10).isEmpty())
    }

    @Test fun boundedReadRangeCannotIncludeAnyEarlierSliceOrCurrentRow() {
        val rows = newspaperRows(30, 2, start = 12, end = 18) { false }
        assertEquals(listOf(13, 14), rows[1].indices)
        assertEquals(listOf(12), newspaperScrolledReadRange(rows, 1, 12, 18).toList())
        assertEquals((12 until 15).toList(), newspaperScrolledReadRange(rows, 2, 12, 18).toList())
        assertTrue(newspaperScrolledReadRange(rows, 0, 12, 18).isEmpty())
    }

    @Test fun emptyAndShrunkenDataClampSourceSafelyWithoutAReadRange() {
        assertEquals(NewspaperPageChange(0, 0), newspaperPageForSource(27, 0, 5, 2, 50) { false })
        val change = newspaperPageForSource(27, 8, 5, 2) { false }
        assertEquals(1, change.page)
        assertEquals(1, change.rowIndex)
        assertNull(change.readRange)
        assertEquals(NewspaperPageChange(0, 0), newspaperPageForSource(-10, 8, 5, 2, -20) { false })
    }
}
