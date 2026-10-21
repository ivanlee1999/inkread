package me.ash.reader.ui.page.home.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewspaperViewportPaginationTest {
    private fun story(index: Int, height: Int, count: Int = 1) = MeasuredNewspaperRow(
        NewspaperRow((index until index + count).toList(), false, false), height)
    private fun date(index: Int, height: Int) = MeasuredNewspaperRow(
        NewspaperRow(listOf(index), true, false), height)

    @Test fun exactFitDoesNotInventAnotherPage() {
        val pages = packNewspaperViewportPages(listOf(story(0, 40), story(1, 60)), 100)
        assertEquals(1, pages.size)
        assertEquals(listOf(0, 1), pages.single().indices)
        assertEquals(100, pages.single().heightPx)
    }

    @Test fun overflowingMosaicMovesWholeRowNotIndividualChildren() {
        val pages = packNewspaperViewportPages(listOf(story(0, 50), story(1, 51, 3)), 100)
        assertEquals(listOf(listOf(0), listOf(1, 2, 3)), pages.map { it.indices })
        assertTrue(pages.all { it.heightPx <= 100 })
    }

    @Test fun dateHeadingTravelsWithFollowingStory() {
        val pages = packNewspaperViewportPages(listOf(story(0, 80), date(1, 10), story(2, 30)), 100)
        assertEquals(listOf(listOf(0), listOf(1, 2)), pages.map { it.indices })
    }

    @Test fun redundantDateMayBeOmittedButItsArticleNeverIs() {
        val pages = packNewspaperViewportPages(listOf(date(0, 15), story(1, 100)), 100)
        assertEquals(listOf(1), pages.single().indices)
        assertEquals(100, pages.single().heightPx)
    }

    @Test fun trailingDateCannotCreateAnEmptyPage() {
        val pages = packNewspaperViewportPages(listOf(story(0, 100), date(1, 10)), 100)
        assertEquals(listOf(listOf(0)), pages.map { it.indices })
    }

    @Test fun emptyEditionHasNoPhantomPage() {
        assertTrue(packNewspaperViewportPages(emptyList(), 100).isEmpty())
    }

    @Test fun everyArticleOccursExactlyOnceAcrossManyViewportHeights() {
        for (height in 80..500 step 7) {
            val rows = (0 until 120).map { story(it, 20 + it % 61) }
            val pages = packNewspaperViewportPages(rows, height)
            assertEquals((0 until 120).toList(), pages.flatMap { it.indices })
            assertTrue(pages.all { it.heightPx <= height && it.rows.isNotEmpty() })
        }
    }

    @Test fun appendedStoriesCannotFillAnAlreadyPrintedLastPage() {
        val original = listOf(story(0, 40), story(1, 20))
        val originalPages = packNewspaperViewportPages(original, 100)
        val breaks = originalPages.map { it.indices.last() }.toSet()
        val appended = packNewspaperViewportPages(original + story(2, 20), 100, breaks)
        assertEquals(listOf(listOf(0, 1), listOf(2)), appended.map { it.indices })
        assertEquals(originalPages.first(), appended.first())
    }

    @Test fun lockedBoundaryAndDateHeadingPreserveSourceOrder() {
        val pages = packNewspaperViewportPages(
            listOf(story(0, 20), date(1, 10), story(2, 30), story(3, 30)), 100, setOf(0))
        assertEquals(listOf(listOf(0), listOf(1, 2, 3)), pages.map { it.indices })
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedStoryMustBeAdaptedBeforePackingNotSilentlyClipped() {
        packNewspaperViewportPages(listOf(story(0, 101)), 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroViewportIsDeferredByLayoutNotAcceptedByPacker() {
        packNewspaperViewportPages(emptyList(), 0)
    }
}
