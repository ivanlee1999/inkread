package me.ash.reader.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedPagePlannerTest {
    private fun group(id: Int, full: Int, compact: Int = full, count: Int = 1, locked: Boolean = false) =
        OrderedPageGroup(listOf(
            OrderedPageChoice("$id-full", full, count),
            OrderedPageChoice("$id-compact", compact, count, orderedCompactPenalty(full, compact)),
        ), locked)

    @Test fun scoresTheWholePageInsteadOfOnlyReconsideringTheLastRow() {
        val pages = planOrderedPages(listOf(group(0, 70, 50), group(1, 70, 50), group(2, 100, 90)), 200)
        assertEquals(listOf(listOf("0-compact", "1-compact", "2-full")), pages)
    }

    @Test fun threeEarlierCompactionsCanBuyAnotherCompleteRealRow() {
        val pages = planOrderedPages(listOf(group(0, 170, 140), group(1, 170, 140),
            group(2, 170, 140), group(3, 180, 150)), 600)
        assertEquals(listOf(listOf("0-full", "1-compact", "2-compact", "3-compact")), pages)
    }

    @Test fun aTinyGapDoesNotAutomaticallyJustifyLosingHeadlineDetail() {
        val pages = planOrderedPages(listOf(group(0, 90), group(1, 20, 10)), 100)
        assertEquals(listOf(listOf("0-full"), listOf("1-full")), pages)
    }

    @Test fun fullPageDetailWinsOverMaximumTinyPreviewCount() {
        val pages = planOrderedPages((0..5).map { group(it, 50, 16) }, 100)
        assertEquals(listOf(listOf("0-full", "1-full"), listOf("2-full", "3-full"),
            listOf("4-full", "5-full")), pages)
    }

    @Test fun detailLossIsSummedPerSourceAndCannotBeDilutedByMoreFullStories() {
        val oneSource = planOrderedPages(listOf(group(0, 80), group(1, 40, 20, count = 1)), 100)
        val fiveSources = planOrderedPages(listOf(group(0, 80), group(1, 40, 20, count = 5)), 100)
        assertEquals(1, oneSource.size)
        assertEquals(2, fiveSources.size)
    }

    @Test fun exactTiesKeepTheEarlierOriginalFullTemplate() {
        val groups = listOf(group(0, 200, 160), group(1, 200, 160))
        repeat(20) {
            assertEquals(listOf(listOf("0-full", "1-compact")), planOrderedPages(groups, 360))
        }
    }

    @Test fun asymmetricSkeletonCandidatesAreComparedAsCompletePageOptions() {
        val first = OrderedPageGroup(listOf(
            OrderedPageChoice("original-feature", 85),
            OrderedPageChoice("asymmetric-lanes", 60),
        ))
        assertEquals(listOf(listOf("asymmetric-lanes", "1-full")),
            planOrderedPages(listOf(first, group(1, 40)), 100))
    }

    @Test fun printedBoundaryBehavesExactlyLikePhysicalEndAfterAppend() {
        val original = listOf(group(0, 70, 50), group(1, 70, 50), group(2, 100, 90))
        val before = planOrderedPages(original, 200)
        val after = planOrderedPages(original.dropLast(1) + original.last().copy(breakAfter = true) +
            listOf(group(3, 10, 5), group(4, 100, 70)), 200)
        assertEquals(before.single(), after.first())
        assertEquals((0..4).toList(), after.flatten().map { it.substringBefore('-').toInt() })
    }

    @Test fun moreThanTheSearchHorizonStillRetainsEveryGroupExactlyOnce() {
        val input = (0 until 205).map { OrderedPageGroup(listOf(OrderedPageChoice(it, 1))) }
        val pages = planOrderedPages(input, 1000)
        assertEquals((0 until 205).toList(), pages.flatten())
        assertTrue(pages.all { it.size in 1..64 })
        assertEquals(pages, planOrderedPages(input, 1000))
    }

    @Test fun mixedLockedBoundariesAndGeometryAlwaysPreserveOrderedFeasibility() {
        for (height in 80..400 step 13) {
            val groups = (0 until 175).map { index ->
                group(index, 20 + index % 59, 15 + index % 41, locked = index % 17 == 16)
            }
            val pages = planOrderedPages(groups, height)
            assertEquals((0 until 175).toList(), pages.flatten().map { it.substringBefore('-').toInt() })
            pages.forEach { page ->
                val used = page.sumOf { value ->
                    val index = value.substringBefore('-').toInt()
                    groups[index].choices.first { it.value == value }.heightPx
                }
                assertTrue(used <= height)
            }
            for (lock in 16 until 175 step 17) {
                assertTrue(pages.any { it.last().substringBefore('-').toInt() == lock })
            }
        }
    }

    @Test fun emptyInputHasNoPhantomPage() {
        assertTrue(planOrderedPages<Int>(emptyList(), 100).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroHeightChoiceCannotCreateANoProgressPath() {
        planOrderedPages(listOf(OrderedPageGroup(listOf(OrderedPageChoice(1, 0)))), 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlargeChoiceCannotBeSilentlyClipped() {
        planOrderedPages(listOf(OrderedPageGroup(listOf(OrderedPageChoice(1, 101)))), 100)
    }
}
