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
}
