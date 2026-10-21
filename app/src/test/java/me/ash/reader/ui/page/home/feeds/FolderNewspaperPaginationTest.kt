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

    @Test fun compactTailFillsSpaceWithAnotherRealSectionBeforeEnrichingDigests() {
        val rows = folderNewspaperRows(sections(3), emptySet(), 1)
        fun measure(row: FolderNewspaperRow, preview: FolderPreview, extra: Int): MeasuredFolderRow {
            val index = rows.indexOf(row)
            val height = if (preview == FolderPreview.Full) 210 + index * 10 + extra * 10
                else 130 + index * 10
            return MeasuredFolderRow(row, height, preview, extra)
        }
        val measured = rows.map { measure(it, FolderPreview.Full, 0) }
        val old = packFolderNewspaperPages(measured, 600)
        val filled = fillFolderNewspaperPages(measured, 600, ::measure)
        assertEquals(2, old.size)
        assertEquals(1, filled.size)
        assertEquals(600, filled.single().heightPx)
        assertTrue(filled.first().heightPx > old.first().heightPx)
        assertEquals(FolderPreview.Compact, filled.single().rows.last().preview)
        assertEquals(2, filled.single().rows.first().extraPublicationNames)
        assertEquals(rows.flatMap { it.keys }, filled.flatMap { it.keys })
    }

    @Test fun completePageChoicesAdmitAnotherSectionWithoutFlatteningEveryRow() {
        val rows = folderNewspaperRows(sections(3), emptySet(), 1)
        fun measure(row: FolderNewspaperRow, preview: FolderPreview, extra: Int): MeasuredFolderRow {
            val index = rows.indexOf(row)
            val height = if (preview == FolderPreview.Full) listOf(220, 220, 240)[index] + extra * 10
                else listOf(160, 160, 200)[index]
            return MeasuredFolderRow(row, height, preview, extra)
        }
        val filled = fillFolderNewspaperPages(rows.map { measure(it, FolderPreview.Full, 0) }, 600, ::measure)
        assertEquals(1, filled.size)
        assertEquals(listOf(FolderPreview.Full, FolderPreview.Compact, FolderPreview.Compact),
            filled.single().rows.map { it.preview })
        assertEquals(600, filled.single().heightPx)
        assertEquals(rows.flatMap { it.keys }, filled.flatMap { it.keys })
    }

    @Test fun wholePagePlanReconsidersTwoEarlierRowsInsteadOfStoppingAtTheLastRow() {
        val rows = folderNewspaperRows(sections(4), emptySet(), 1)
        fun measure(row: FolderNewspaperRow, preview: FolderPreview, extra: Int): MeasuredFolderRow {
            val index = rows.indexOf(row)
            val base = if (preview == FolderPreview.Full) listOf(170, 170, 170, 180)[index]
                else listOf(140, 140, 140, 150)[index]
            return MeasuredFolderRow(row, base + extra * 24, preview, extra)
        }
        val measured = rows.map { measure(it, FolderPreview.Full, 0) }
        // After three Full rows, even replacing the last one leaves 630px for four rows.
        assertTrue(170 + 170 + 140 + 150 > 600)
        val filled = fillFolderNewspaperPages(measured, 600, ::measure)
        assertEquals(1, filled.size)
        assertEquals(600, filled.single().heightPx)
        assertEquals(4, filled.single().rows.size)
        assertEquals(1, filled.single().rows.count { it.preview == FolderPreview.Full })
        assertEquals(3, filled.single().rows.count { it.preview == FolderPreview.Compact })
        assertEquals(rows.flatMap { it.keys }, filled.single().keys)
    }

    @Test fun equalPageScoresDeterministicallyPreserveEarlierPreferredPreview() {
        val rows = folderNewspaperRows(sections(2), emptySet(), 1)
        fun measure(row: FolderNewspaperRow, preview: FolderPreview, extra: Int) =
            MeasuredFolderRow(row, (if (preview == FolderPreview.Full) 200 else 160) + extra * 24, preview, extra)
        val measured = rows.map { measure(it, FolderPreview.Full, 0) }
        val first = fillFolderNewspaperPages(measured, 360, ::measure)
        assertEquals(listOf(FolderPreview.Full, FolderPreview.Compact), first.single().rows.map { it.preview })
        repeat(25) {
            assertEquals(first, fillFolderNewspaperPages(measured, 360, ::measure))
        }
    }

    @Test fun aCompactAttemptThatCannotAdmitNextRowDoesNotDiscardDetail() {
        val rows = folderNewspaperRows(sections(3), emptySet(), 1)
        fun measure(row: FolderNewspaperRow, preview: FolderPreview, extra: Int) =
            MeasuredFolderRow(row, (if (preview == FolderPreview.Full) 200 else 190) + extra * 24, preview, extra)
        val filled = fillFolderNewspaperPages(rows.map { measure(it, FolderPreview.Full, 0) }, 480, ::measure)
        assertEquals(2, filled.size)
        assertTrue(filled.flatMap { it.rows }.all { it.preview == FolderPreview.Full })
        assertEquals(rows.flatMap { it.keys }, filled.flatMap { it.keys })
    }

    @Test fun realDigestNamesFillSpareRoomButSparseFinalPagesStayNatural() {
        val row = folderNewspaperRows(sections(1, 7), emptySet(), 1).single()
        val requests = mutableListOf<Int>()
        fun measure(row: FolderNewspaperRow, preview: FolderPreview, extra: Int): MeasuredFolderRow {
            requests += extra
            return MeasuredFolderRow(row, 200 + extra * 24, preview, extra)
        }
        val filled = fillFolderNewspaperPages(listOf(MeasuredFolderRow(row, 200)), 800, ::measure)
        assertEquals(4, filled.single().rows.single().extraPublicationNames)
        assertEquals(296, filled.single().heightPx)
        assertTrue("Only actual remaining names can be requested", requests.all { it in 0..4 })
        assertEquals(row.keys, filled.single().keys)

        val empty = folderNewspaperRows(sections(1, 0), emptySet(), 1).single()
        val sparse = fillFolderNewspaperPages(listOf(MeasuredFolderRow(empty, 126)), 800) { _, _, _ ->
            error("A folder without publications cannot fabricate a digest")
        }
        assertEquals(126, sparse.single().heightPx)
        assertEquals(0, sparse.single().rows.single().extraPublicationNames)
    }

    @Test fun nativeMeasurementCandidatesAreBoundedEvenWithThousandsOfSubscriptions() {
        val row = folderNewspaperRows(sections(1, 2_000), emptySet(), 1).single()
        var calls = 0
        val filled = fillFolderNewspaperPages(listOf(MeasuredFolderRow(row, 200)), 100_000) { actual, preview, extra ->
            calls++
            MeasuredFolderRow(actual, 200 + extra * 24, preview, extra)
        }
        assertEquals(32, filled.single().rows.single().extraPublicationNames)
        assertTrue("Binary search has a constant small measurement bound", calls <= 6)
    }

    @Test fun wholePagePlanningKeepsNativeMeasurementsLinearInSourceRows() {
        val rows = folderNewspaperRows(sections(80, 40), emptySet(), 1)
        val calls = mutableMapOf<String, Int>()
        fun measure(row: FolderNewspaperRow, preview: FolderPreview, extra: Int): MeasuredFolderRow {
            calls[row.key] = calls.getOrDefault(row.key, 0) + 1
            return MeasuredFolderRow(row, (if (preview == FolderPreview.Full) 180 else 120) + extra * 24, preview, extra)
        }
        val measured = rows.map { MeasuredFolderRow(it, 180) }
        val filled = fillFolderNewspaperPages(measured, 640, ::measure)
        assertTrue(filled.size > 1)
        assertTrue("One Compact probe, at most one restore probe and six detail probes per row",
            calls.values.all { it <= 8 })
        assertEquals(rows.flatMap { it.keys }, filled.flatMap { it.keys })
        assertTrue(filled.all { it.heightPx <= 640 })
    }

    @Test fun restoreLaterFullTitleAndDigestBeforeAddingEarlierExtraNames() {
        val rows = folderNewspaperRows(sections(2), emptySet(), 1)
        val measured = listOf(MeasuredFolderRow(rows[0], 100),
            MeasuredFolderRow(rows[1], 100, FolderPreview.Compact))
        val filled = fillFolderNewspaperPages(measured, 260) { row, preview, extra ->
            val base = if (row == rows[1] && preview == FolderPreview.Full) 150 else 100
            MeasuredFolderRow(row, base + extra * 20, preview, extra)
        }
        assertEquals(listOf(FolderPreview.Full, FolderPreview.Full), filled.single().rows.map { it.preview })
        assertEquals(listOf(0, 0), filled.single().rows.map { it.extraPublicationNames })
        assertEquals(250, filled.single().heightPx)
    }

    @Test fun enrichedMixedEditionsKeepExactSourceOrderAndAnchorsAtEveryViewport() {
        val data = sections(18)
        for (columns in 1..4) {
            val rows = folderNewspaperRows(data, setOf("g2", "g6", "g12"), columns)
            val expected = rows.flatMap { it.keys }
            fun measure(row: FolderNewspaperRow, preview: FolderPreview, extra: Int): MeasuredFolderRow {
                val index = rows.indexOf(row)
                val base = 110 + (index % 4) * 35
                val height = (if (preview == FolderPreview.Full) base else base - 35) + extra * 18
                return MeasuredFolderRow(row, height, preview, extra)
            }
            for (height in listOf(240, 320, 444, 720)) {
                val measured = rows.map { measure(it, FolderPreview.Full, 0) }
                val pages = fillFolderNewspaperPages(measured, height, ::measure)
                assertEquals(expected, pages.flatMap { it.keys })
                assertEquals(expected.size, pages.flatMap { it.keys }.distinct().size)
                assertTrue(pages.all { it.heightPx <= height && it.rows.isNotEmpty() })
                assertEquals("Same native measurements always give the same edition", pages,
                    fillFolderNewspaperPages(measured, height, ::measure))
                val page = folderPageForAnchor(pages, "feed:g12f3", "g12", 0)
                assertTrue("feed:g12f3" in pages[page].keys)
            }
        }
    }

    @Test fun tinyViewportFallbacksAreNotUsedToDensifyOtherwiseReadableRows() {
        val rows = folderNewspaperRows(sections(3), emptySet(), 1)
        val fallback = rows.map { MeasuredFolderRow(it, 48, FolderPreview.HeadlineOnly) }
        val pages = fillFolderNewspaperPages(fallback, 60) { _, _, _ ->
            error("A viewport fallback cannot be expanded or compressed again")
        }
        assertEquals(3, pages.size)
        assertEquals(fallback, pages.flatMap { it.rows })
    }

    @Test fun logicalReadingSourceSurvivesConsecutivePageBoundaryChanges() {
        val rows = folderNewspaperRows(sections(4), emptySet(), 1).map { MeasuredFolderRow(it, 100) }
        val initial = listOf(FolderNewspaperPage(rows.take(2)), FolderNewspaperPage(rows.drop(2)))
        var anchor = folderReadingAnchor(initial[1])!!
        assertEquals("folder:g2", anchor.key)

        val reflow = listOf(FolderNewspaperPage(rows.take(1)), FolderNewspaperPage(rows.drop(1)))
        val reflowPage = folderPageForAnchor(reflow, anchor.key, anchor.sectionId, 1)
        assertEquals("folder:g1", reflow[reflowPage].keys.first())
        anchor = folderReadingAnchor(reflow[reflowPage], anchor)!!
        assertEquals("The first automatic reflow cannot replace the reading source", "folder:g2", anchor.key)

        val nextPage = folderPageForAnchor(initial, anchor.key, anchor.sectionId, reflowPage)
        assertEquals(1, nextPage)
        assertTrue(anchor.key in initial[nextPage].keys)
        assertEquals(anchor, folderReadingAnchor(initial[nextPage], anchor))
    }

    @Test fun explicitPageNavigationCanChooseNewFirstSourceWithoutRetainingOldAnchor() {
        val rows = folderNewspaperRows(sections(4), emptySet(), 1).map { MeasuredFolderRow(it, 100) }
        val page = FolderNewspaperPage(rows.drop(1))
        val retained = FolderReadingAnchor("folder:g2", "g2")
        assertEquals(retained, folderReadingAnchor(page, retained))
        assertEquals(FolderReadingAnchor("folder:g1", "g1"), folderReadingAnchor(page))
    }

    @Test fun removedPublicationRetainsItsRealFolderRatherThanEarlierPageSource() {
        val rows = folderNewspaperRows(sections(5), emptySet(), 1).map { MeasuredFolderRow(it, 100) }
        val page = FolderNewspaperPage(rows.drop(1))
        val resolved = folderReadingAnchor(page, FolderReadingAnchor("feed:g3f2", "g3"))
        assertEquals(FolderReadingAnchor("folder:g3", "g3"), resolved)
    }

    @Test fun temporaryEmptyEditionRetainsAnchorButRemovedSectionFindsRealVisibleSource() {
        val original = FolderReadingAnchor("feed:g3f2", "g3")
        assertEquals(original, folderReadingAnchor(null, original))
        assertEquals(original, folderReadingAnchor(FolderNewspaperPage(emptyList()), original))
        val row = folderNewspaperRows(sections(1), emptySet(), 1).single()
        assertEquals(FolderReadingAnchor("folder:g0", "g0"),
            folderReadingAnchor(FolderNewspaperPage(listOf(MeasuredFolderRow(row, 100))), original))
    }

    @Test fun retainedAnchorResolvesOwningSectionFromItsSlotNotTheFirstColumn() {
        val rows = folderNewspaperRows(sections(4), emptySet(), 2).map { MeasuredFolderRow(it, 100) }
        val page = FolderNewspaperPage(rows)
        assertEquals(FolderReadingAnchor("folder:g2", "g2"),
            folderReadingAnchor(page, FolderReadingAnchor("folder:g2", null)))
    }
}
