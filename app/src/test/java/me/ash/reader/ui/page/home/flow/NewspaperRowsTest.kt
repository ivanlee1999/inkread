package me.ash.reader.ui.page.home.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewspaperRowsTest {
    @Test fun twoColumnsPreserveEverySourceIndexAndDateBoundary() {
        val rows = newspaperRows(10, 2) { it == 0 || it == 5 }
        assertEquals((0..9).toList(), rows.flatMap { it.indices })
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6), listOf(7, 8), listOf(9)), rows.map { it.indices })
        assertEquals(listOf(1, 6), rows.filter { it.isLead }.flatMap { it.indices })
        assertTrue(rows.filter { it.isDate }.all { it.indices.size == 1 && !it.isLead })
    }

    @Test fun narrowRowsAreSingleColumnWithoutReordering() {
        val rows = newspaperRows(6, 1) { it == 0 }
        assertEquals((0..5).map { listOf(it) }, rows.map { it.indices })
        assertEquals(1, rows.count { it.isLead })
    }

    @Test fun pageSlicePreservesAbsoluteIndicesAndStartsWithLead() {
        val rows = newspaperRows(15, 2, start = 6, end = 11) { it == 9 }
        assertEquals(listOf(listOf(6), listOf(7, 8), listOf(9), listOf(10)), rows.map { it.indices })
        assertEquals(listOf(6, 10), rows.filter { it.isLead }.flatMap { it.indices })
        assertEquals(1, rows.rowForSourceIndex(8))
        assertEquals(3, rows.rowForSourceIndex(10))
    }

    @Test fun emptyAndClampedRangesAreSafe() {
        assertTrue(newspaperRows(0, 2) { false }.isEmpty())
        assertTrue(newspaperRows(4, 2, start = 99) { false }.isEmpty())
        assertEquals((0..3).toList(), newspaperRows(4, 99, start = -2, end = 99) { false }.flatMap { it.indices })
    }
    @Test fun threeColumnsAlternateFeatureBriefStacksAndEqualLanes() {
        val rows = newspaperRows(10, 3) { false }
        assertEquals(listOf(listOf(0), listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)), rows.map { it.indices })
        assertEquals(listOf(listOf(1), listOf(2, 3)), rows[1].slots.map { it.indices })
        assertEquals(listOf(2, 1), rows[1].slots.map { it.span })
        assertEquals(listOf(NewspaperStoryStyle.Feature, NewspaperStoryStyle.Brief), rows[1].slots.map { it.style })
        assertEquals(listOf(1, 1, 1), rows[2].slots.map { it.span })
    }

    @Test fun fourColumnsHaveTwoBriefStacksWithoutReordering() {
        val rows = newspaperRows(15, 4) { false }
        assertEquals(listOf(listOf(0), (1..5).toList(), (6..9).toList(), (10..14).toList()), rows.map { it.indices })
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5)), rows[1].slots.map { it.indices })
        assertEquals(listOf(2, 1, 1), rows[1].slots.map { it.span })
        assertEquals(listOf(4), rows[0].slots.map { it.span })
    }

    @Test fun partialFourLaneEditorialGroupFillsRemainingWidthWithoutChangingRows() {
        val rows = newspaperRows(4, 4) { false }
        assertEquals(listOf(listOf(0), listOf(1, 2, 3)), rows.map { it.indices })
        assertEquals(listOf(listOf(1), listOf(2, 3)), rows[1].slots.map { it.indices })
        assertEquals(listOf(2, 2), rows[1].slots.map { it.span })
        assertEquals(4, rows[1].slots.sumOf { it.span })
        (1..3).forEach { assertEquals(1, rows.rowForSourceIndex(it)) }
        // Ordinary three-lane and fuller four-lane templates are unaffected.
        assertEquals(listOf(2, 1), newspaperRows(4, 3) { false }[1].slots.map { it.span })
        assertEquals(listOf(2, 1, 1), newspaperRows(5, 4) { false }[1].slots.map { it.span })
        assertEquals(listOf(2, 1, 1), newspaperRows(6, 4) { false }[1].slots.map { it.span })
    }

    @Test fun partialEditorialWidthAlsoFillsAtDateAndAbsolutePageBoundaries() {
        val atDate = newspaperRows(8, 4) { it == 4 }
        assertEquals(listOf(1, 2, 3), atDate[1].indices)
        assertEquals(listOf(2, 2), atDate[1].slots.map { it.span })
        assertEquals(listOf(4), atDate[2].indices)
        assertTrue(atDate[2].isDate)
        assertEquals((0..7).toList(), atDate.flatMap { it.indices })
        val atPageEnd = newspaperRows(30, 4, start = 8, end = 12) { false }
        assertEquals(listOf(listOf(8), listOf(9, 10, 11)), atPageEnd.map { it.indices })
        assertEquals(listOf(2, 2), atPageEnd[1].slots.map { it.span })
        assertEquals(1, atPageEnd.rowForSourceIndex(11))
    }

    @Test fun shortSectionNeverConsumesNextDateAndTemplateResets() {
        val rows = newspaperRows(13, 4) { it == 4 }
        assertEquals(listOf(listOf(0), listOf(1, 2, 3), listOf(4), listOf(5), (6..10).toList(), listOf(11, 12)), rows.map { it.indices })
        assertEquals(listOf(0, 5), rows.filter { it.isLead }.flatMap { it.indices })
        assertEquals(listOf(2, 1, 1), rows[4].slots.map { it.span })
        assertEquals(listOf(1, 1), rows[5].slots.map { it.span })
    }

    @Test fun pageSlicesResetEditorialTemplateUsingAbsoluteSourceIndices() {
        val rows = newspaperRows(30, 4, start = 8, end = 18) { false }
        assertEquals(listOf(listOf(8), (9..13).toList(), (14..17).toList()), rows.map { it.indices })
        assertEquals(listOf(listOf(9), listOf(10, 11), listOf(12, 13)), rows[1].slots.map { it.indices })
        assertEquals(1, rows.rowForSourceIndex(13))
        assertEquals(2, rows.rowForSourceIndex(14))
    }

    @Test fun allLaneCountsRangesAndDateBoundariesPreserveExactlyOneSourceIdentity() {
        (-1..6).forEach { requestedColumns ->
            val columns = requestedColumns.coerceIn(1, 4)
            (0..35).forEach { count ->
                (0..count).forEach { start ->
                    listOf(start, (start + 1).coerceAtMost(count), count).forEach { end ->
                        val rows = newspaperRows(count, requestedColumns, start, end) { it % 7 == 0 }
                        assertEquals((start until end).toList(), rows.flatMap { it.indices })
                        rows.forEachIndexed { rowIndex, row ->
                            assertEquals(row.indices, row.slots.flatMap { it.indices })
                            assertTrue(row.slots.all { it.span in 1..columns && it.indices.size in 1..2 })
                            assertTrue(row.slots.sumOf { it.span } <= columns)
                            row.indices.forEach { assertEquals(rowIndex, rows.rowForSourceIndex(it)) }
                            if (row.isDate) assertEquals(1, row.indices.size)
                            else assertTrue(row.indices.none { it % 7 == 0 })
                            if (row.isLead) assertEquals(1, row.indices.size)
                        }
                    }
                }
            }
        }
    }

}
