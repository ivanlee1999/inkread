package me.ash.reader.ui.page.home.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewspaperViewportFillTest {
    private fun story(index: Int, full: Int, compact: Int = full, count: Int = 1): List<MeasuredNewspaperRow> {
        val row = NewspaperRow((index until index + count).toList(), false, false)
        return listOf(MeasuredNewspaperRow(row, full), MeasuredNewspaperRow(row, compact, NewspaperPreview.Compact))
    }
    private fun pack(rows: List<List<MeasuredNewspaperRow>>, height: Int, breaks: Set<Int> = emptySet()) =
        packNewspaperViewportVariants(rows.size, height, breaks) { rows[it] }

    @Test fun compactNextRowUsesOtherwiseEmptyBottomForRealStories() {
        val pages = pack(listOf(story(0, 60), story(1, 55, 40, 2)), 100)
        assertEquals(listOf(0, 1, 2), pages.single().indices)
        assertEquals(100, pages.single().heightPx)
        assertEquals(listOf(NewspaperPreview.Full, NewspaperPreview.Compact), pages.single().rows.map { it.preview })
    }

    @Test fun earlierRowCanCompactToAdmitAnotherWholeRow() {
        val pages = pack(listOf(story(0, 65, 40), story(1, 60, 50)), 100)
        assertEquals(listOf(0, 1), pages.single().indices)
        assertEquals(listOf(NewspaperPreview.Compact, NewspaperPreview.Full), pages.single().rows.map { it.preview })
    }

    @Test fun wholePageSearchCanReconsiderTwoEarlierRowsTogether() {
        val pages = pack(listOf(story(0, 70, 50), story(1, 70, 50), story(2, 100, 90)), 200)
        assertEquals(listOf(0, 1, 2), pages.single().indices)
        assertEquals(listOf(NewspaperPreview.Compact, NewspaperPreview.Compact, NewspaperPreview.Full),
            pages.single().rows.map { it.preview })
    }

    @Test fun beamPruningCannotLeaveARestorableCompactHeadlineBeforeExcerptGrowth() {
        val geometry = listOf(Triple(94, 34, 4), Triple(116, 104, 1), Triple(220, 168, 3),
            Triple(177, 112, 2), Triple(97, 93, 1), Triple(311, 175, 5),
            Triple(71, 29, 1), Triple(211, 183, 2))
        var index = 0
        val rows = geometry.map { (full, compact, count) ->
            story(index, full, compact, count).also { index += count }
        }
        val pages = pack(rows, 925)
        val restored = pages.first().rows.first { it.row.indices == listOf(4) }
        assertEquals(NewspaperPreview.Full, restored.preview)
        assertEquals(116, restored.heightPx)
        pages.forEach { page ->
            val spare = 925 - page.heightPx
            page.rows.filter { it.preview == NewspaperPreview.Compact }.forEach { compact ->
                val full = rows.first { it.first().row.indices == compact.row.indices }.first()
                assertTrue("Restore matching Full before spending spare space on excerpts",
                    full.heightPx > compact.heightPx + spare)
            }
        }
    }

    @Test fun unsuccessfulCompactionLeavesRicherPageUntouched() {
        val pages = pack(listOf(story(0, 80, 60), story(1, 75, 65)), 100)
        assertEquals(listOf(listOf(0), listOf(1)), pages.map { it.indices })
        assertTrue(pages.all { it.rows.single().preview == NewspaperPreview.Full })
    }

    @Test fun lockedPageCannotRebalanceForLaterAppend() {
        val rows = listOf(story(0, 65, 40), story(1, 60, 50))
        val original = pack(rows, 100)
        val appended = pack(rows + listOf(story(2, 20, 10)), 100, original.map { it.indices.last() }.toSet())
        assertEquals(original.single(), appended.first())
        assertEquals(listOf(listOf(0, 1), listOf(2)), appended.map { it.indices })
    }

    @Test fun dateStaysWithStoryAndNeverBecomesAnEmptyPage() {
        val date = listOf(MeasuredNewspaperRow(NewspaperRow(listOf(1), true, false), 10))
        val pages = pack(listOf(story(0, 70), date, story(2, 70, 30),
            listOf(MeasuredNewspaperRow(NewspaperRow(listOf(3), true, false), 10))), 100)
        assertEquals(listOf(listOf(0), listOf(1, 2)), pages.map { it.indices })
    }

    @Test fun boundedExpansionFillsShortLanesThenRemainingPageWithWholeTextLines() {
        val row = MeasuredNewspaperRow(NewspaperRow(listOf(0, 1), false, false), 80)
        var measurements = 0
        val expanded = enrichNewspaperViewportPage(NewspaperViewportPage(listOf(row)), 120) { candidate ->
            measurements++
            candidate.copy(heightPx = maxOf(40 + 10 * (candidate.extraDescriptionLines[0] ?: 0),
                80 + 10 * (candidate.extraDescriptionLines[1] ?: 0)))
        }
        assertEquals(listOf(0, 1), expanded.indices)
        assertEquals(120, expanded.heightPx)
        assertEquals(8, expanded.rows.single().extraDescriptionLines[0])
        assertEquals(4, expanded.rows.single().extraDescriptionLines[1])
        assertTrue("At most two six-probe searches per story", measurements <= 24)
    }

    @Test fun compactHeadlineContextGetsSpaceBeforeLongerExcerpts() {
        val row = story(0, 40).first().copy(preview = NewspaperPreview.Compact,
            row = NewspaperRow(listOf(0), false, true))
        val expanded = enrichNewspaperViewportPage(NewspaperViewportPage(listOf(row)), 60) { candidate ->
            candidate.copy(heightPx = 40 + 10 * (candidate.extraHeadlineLines[0] ?: 0) +
                10 * (candidate.extraDescriptionLines[0] ?: 0))
        }
        assertEquals(2, expanded.rows.single().extraHeadlineLines[0])
        assertEquals(0, expanded.rows.single().extraDescriptionLines[0] ?: 0)
        assertEquals(60, expanded.heightPx)
    }

    @Test fun compactNonLeadHeadlineNeverExceedsItsFullFourLineLimit() {
        val row = story(0, 40).first().copy(preview = NewspaperPreview.Compact)
        val expanded = enrichNewspaperViewportPage(NewspaperViewportPage(listOf(row)), 100) { candidate ->
            candidate.copy(heightPx = 40 + 10 * (candidate.extraHeadlineLines[0] ?: 0))
        }
        assertEquals(1, expanded.rows.single().extraHeadlineLines[0])
        assertEquals(50, expanded.heightPx)
    }

    @Test fun exhaustedRealContentDoesNotStretchItsBlock() {
        var measurements = 0
        val row = story(0, 30).first()
        val expanded = enrichNewspaperViewportPage(NewspaperViewportPage(listOf(row)), 100) { candidate ->
            measurements++
            candidate.copy(heightPx = 30)
        }
        assertEquals(30, expanded.heightPx)
        assertEquals(1, measurements)
    }

    @Test fun fallbackHeadlinesAreNotUsedAsDensityExpansion() {
        val row = story(0, 40).first().copy(preview = NewspaperPreview.HeadlineOnly)
        val expanded = enrichNewspaperViewportPage(NewspaperViewportPage(listOf(row)), 100) {
            error("A safety fallback must not be treated as a richer density preview")
        }
        assertEquals(row, expanded.rows.single())
    }

    @Test fun everyViewportAndLockedBoundaryKeepAllWholeSourceRowsExactlyOnce() {
        for (height in 80..420 step 7) {
            val rows = (0 until 120).map { story(it, 25 + it % 56, 20 + it % 31) }
            val pages = pack(rows, height, setOf(19, 39, 59, 79, 99))
            assertEquals((0 until 120).toList(), pages.flatMap { it.indices })
            assertTrue(pages.all { it.heightPx <= height && it.rows.isNotEmpty() })
            assertTrue(listOf(19, 39, 59, 79, 99).all { locked -> pages.any { it.indices.last() == locked } })
            assertEquals(pages, pack(rows, height, setOf(19, 39, 59, 79, 99)))
        }
    }

    @Test fun sparseTailUsesAllAvailableColumnsBeforeComparingPreviewHeights() {
        val row = NewspaperRow(listOf(8), false, false)
        for (columns in 2..4) {
            val templates = newspaperViewportTemplates(row, columns)
            assertEquals(1, templates.size)
            assertEquals(listOf(8), templates.single().indices)
            assertEquals(columns, templates.single().slots.single().span)
        }
        val editorial = newspaperRows(6, 4) { false }[1]
        assertEquals(editorial, newspaperViewportTemplates(editorial, 4).first())
    }

    @Test fun alternativeTemplatesKeepSourceOrderAndFillPartialWidth() {
        for (columns in 1..4) {
            newspaperRows(35, columns) { it % 9 == 0 }.forEach { row ->
                val alternatives = newspaperViewportTemplates(row, columns)
                assertTrue(alternatives.size <= 2)
                alternatives.forEach {
                    assertEquals(row.indices, it.indices)
                    assertEquals(row.indices, it.slots.flatMap { slot -> slot.indices })
                    assertTrue(it.slots.sumOf { slot -> slot.span } <= columns)
                }
                if (!row.isDate && row.indices.size <= columns) {
                    assertEquals(columns, alternatives.last().slots.sumOf { it.span })
                }
            }
        }
    }
}
