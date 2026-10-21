package me.ash.reader.ui.page.home.flow

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for edition identity; native tests exercise production text measurement. */
class NewspaperViewportStateTest {
    private val feed = Feed("feed", "Publication", url = "https://example.com/feed", groupId = "group", accountId = 1)

    private fun article(id: String, time: Long = 0, unread: Boolean = true, starred: Boolean = false) =
        ArticleFlowItem.Article(ArticleWithFeed(Article(
            id = id, date = Date(time), title = "Complete headline $id", rawDescription = "",
            shortDescription = "Complete preview $id", link = "https://example.com/$id",
            feedId = feed.id, accountId = 1, isUnread = unread, isStarred = starred,
        ).apply { dateString = "12:30" }, feed))

    private fun ids(state: NewspaperViewportState) = state.items.filterIsInstance<ArticleFlowItem.Article>()
        .map { it.articleWithFeed.article.id }
    private fun displayed(state: NewspaperViewportState) = state.displayedArticles.map { it.article.id }
    private fun pages(vararg indices: List<Int>) = indices.map { sources ->
        NewspaperViewportPage(listOf(MeasuredNewspaperRow(NewspaperRow(sources, false, false), 50)))
    }
    private fun present(state: NewspaperViewportState) = state.presented(state.displayedIndices)
    private fun nextWithoutMarking(state: NewspaperViewportState) {
        present(state)
        assertTrue(state.turn(true, false) { throw AssertionError("Disabled read preference marked an article") })
    }

    @Test fun appendedTallStoryCannotFillThePrintedIncompleteRowOrPage() {
        val pager = Any()
        val state = NewspaperViewportState()
        state.acceptSnapshot(pager, listOf(article("a", 3), article("b", 2)))
        val originalRows = state.rows(2)
        val originalPages = packNewspaperViewportPages(originalRows.map { MeasuredNewspaperRow(it, 40) }, 100)
        state.updatePages(originalPages, "portrait")
        present(state)
        val before = displayed(state)

        state.acceptSnapshot(pager, listOf(article("a", 3), article("b", 2), article("c", 1)))
        val appendedRows = state.rows(2)
        assertEquals(originalRows, appendedRows.take(originalRows.size))
        val measured = appendedRows.map { MeasuredNewspaperRow(it, if (2 in it.indices) 90 else 40) }
        state.updatePages(packNewspaperViewportPages(measured, 100, state.pageBreaksFor("portrait")), "portrait")

        assertEquals(before, displayed(state))
        assertEquals(originalPages.first(), state.pages.first())
        assertEquals(listOf(0, 1, 2), state.pages.flatMap { it.indices })
    }

    @Test fun readerReturnRetainsExactPageAfterEntireDisplayedUnreadPageDisappears() {
        val pager = Any()
        val state = NewspaperViewportState()
        state.acceptSnapshot(pager, listOf(article("a", 3), article("b", 2), article("c", 1)))
        state.updatePages(pages(listOf(0), listOf(1), listOf(2)), "portrait")
        nextWithoutMarking(state)
        present(state)
        val before = state.pages[state.currentPage]
        state.recordReadStatus("b", false)

        state.acceptSnapshot(pager, listOf(article("a", 3), article("c", 1)))

        assertEquals(1, state.currentPage)
        assertEquals(before, state.pages[state.currentPage])
        assertEquals(listOf("b"), displayed(state))
        assertFalse(state.displayedArticles.single().article.isUnread)
    }

    @Test fun rapidTurnCannotMarkAnIncomingPageBeforeItWasPresented() {
        val state = NewspaperViewportState()
        state.acceptSnapshot(Any(), listOf(article("a"), article("b"), article("c")))
        state.updatePages(pages(listOf(0), listOf(1), listOf(2)), "portrait")
        present(state)
        val marked = mutableListOf<String>()
        val mark: (List<ArticleWithFeed>) -> Unit = { marked += it.map { entry -> entry.article.id } }

        assertTrue(state.turn(true, true, mark))
        assertFalse(state.turn(true, true, mark))
        assertEquals(listOf("a"), marked)
        assertEquals(1, state.currentPage)
        present(state)
        assertTrue(state.turn(true, true, mark))
        assertEquals(listOf("a", "b"), marked)
    }

    @Test fun backwardBoundaryAndDisabledPreferenceNeverMark() {
        val state = NewspaperViewportState()
        state.acceptSnapshot(Any(), listOf(article("a"), article("b")))
        state.updatePages(pages(listOf(0), listOf(1)), "portrait")
        val unexpectedMark: (List<ArticleWithFeed>) -> Unit = { throw AssertionError("Unexpected read mutation") }
        present(state)
        assertFalse(state.turn(false, true, unexpectedMark))
        assertTrue(state.turn(true, false, unexpectedMark))
        present(state)
        assertFalse(state.turn(true, true, unexpectedMark))
        assertTrue(state.turn(false, true, unexpectedMark))
    }

    @Test fun statusesRemainCurrentWithoutChangingFrozenContentOrEmphasis() {
        val pager = Any()
        val state = NewspaperViewportState()
        state.acceptSnapshot(pager, listOf(article("a", unread = true)))
        val frozen = (state.items.single() as ArticleFlowItem.Article).articleWithFeed
        state.recordReadStatus("a", false)
        state.recordStarredStatus("a", true)
        // An unchanged pre-commit source must not undo optimistic flags.
        state.acceptSnapshot(pager, listOf(article("a", unread = true)))
        assertFalse(state.currentArticle(frozen).article.isUnread)
        assertTrue(state.currentArticle(frozen).article.isStarred)
        // DB acknowledgment and a later toggle still work after the external diff map clears.
        state.acceptSnapshot(pager, listOf(article("a", unread = false, starred = true)))
        state.recordReadStatus("a", true)
        state.recordStarredStatus("a", false)
        assertTrue(state.currentArticle(frozen).article.isUnread)
        assertFalse(state.currentArticle(frozen).article.isStarred)
        assertTrue(frozen.article.isUnread)
        assertFalse(frozen.article.isStarred)
        assertEquals("Complete headline a", frozen.article.title)
        assertEquals("12:30", state.currentArticle(frozen).article.dateString)
    }

    @Test fun resizeUsesVisibleSourceAnchorAndRequiresNewPresentationBeforeTurning() {
        val state = NewspaperViewportState()
        state.acceptSnapshot(Any(), listOf(article("a"), article("b"), article("c"), article("d")))
        state.updatePages(pages(listOf(0), listOf(1), listOf(2), listOf(3)), "portrait")
        nextWithoutMarking(state)
        nextWithoutMarking(state)
        present(state)
        val revision = state.layoutRevision

        state.updatePages(pages(listOf(0, 1), listOf(2, 3)), "landscape")

        assertEquals(1, state.currentPage)
        assertTrue("Visible anchor survives reflow", "c" in displayed(state))
        assertTrue(state.layoutRevision > revision)
        assertFalse(state.turn(false, true) { throw AssertionError("Resize marked a page") })
        present(state)
        assertTrue(state.turn(false, true) { throw AssertionError("Backward reflow marked a page") })
    }

    @Test fun repeatedWideNarrowReflowsRetainTheLogicalArticleRatherThanTheNewPageFirst() {
        val pager = Any()
        val state = NewspaperViewportState()
        state.acceptSnapshot(pager, listOf(article("a", 4), article("b", 3), article("c", 2), article("d", 1)))
        val narrow = pages(listOf(0), listOf(1), listOf(2), listOf(3))
        state.updatePages(narrow, "narrow")
        nextWithoutMarking(state)
        nextWithoutMarking(state)
        present(state)
        repeat(3) {
            state.updatePages(pages(listOf(0, 1, 2), listOf(3)), "wide")
            assertTrue("c" in displayed(state))
            present(state)
            state.recordReadStatus("c", false)
            state.updatePages(narrow, "narrow")
            assertEquals(listOf("c"), displayed(state))
            assertFalse(state.turn(true, true) { error("Unpresented reflow marked articles") })
            present(state)
        }
        // A real navigation changes the logical position; it is not permanently pinned to c.
        assertTrue(state.turn(true, false) {})
        present(state)
        state.updatePages(pages(listOf(0, 1, 2, 3)), "all-wide")
        present(state)
        state.updatePages(narrow, "narrow-again")
        assertEquals(listOf("d"), displayed(state))
    }

    @Test fun resettingEditionClearsTheLogicalArticleAnchor() {
        val state = NewspaperViewportState()
        state.acceptSnapshot(Any(), listOf(article("a"), article("b")))
        state.updatePages(pages(listOf(0), listOf(1)), "narrow")
        nextWithoutMarking(state)
        present(state)
        state.acceptSnapshot(Any(), listOf(article("new"), article("b")))
        state.updatePages(pages(listOf(0), listOf(1)), "narrow")
        assertEquals(listOf("new"), displayed(state))
    }

    @Test fun explicitAscendingDirectionSupportsSingleStoryAndEqualDateDisjointContinuation() {
        for (nextDate in listOf(10L, 11L)) {
            val pager = Any()
            val state = NewspaperViewportState()
            state.acceptSnapshot(pager, listOf(article("a", 10)), ascending = true)
            state.acceptSnapshot(pager, listOf(article("b", nextDate)), ascending = true)
            assertEquals(listOf("a", "b"), ids(state))
        }
    }

    @Test fun prependedAndInterleavedSyncStoriesWaitForExplicitRefresh() {
        val pager = Any()
        val state = NewspaperViewportState()
        state.acceptSnapshot(pager, listOf(article("a", 30), article("b", 20)))
        state.acceptSnapshot(pager, listOf(article("new", 40), article("a", 30),
            article("interleaved", 25), article("b", 20), article("c", 10)))
        assertEquals(listOf("a", "b", "c"), ids(state))
        state.reset()
        state.acceptSnapshot(pager, listOf(article("new", 40), article("a", 30)))
        assertEquals(listOf("new", "a"), ids(state))
    }

    @Test fun newPagerCannotInheritOldEditionOrPresentation() {
        val first = Any()
        val replacement = Any()
        val state = NewspaperViewportState()
        state.acceptSnapshot(first, listOf(article("a"), article("b")))
        state.updatePages(pages(listOf(0), listOf(1)), "portrait")
        nextWithoutMarking(state)
        state.acceptSnapshot(replacement, listOf(article("new")))
        assertEquals(listOf("new"), ids(state))
        assertTrue(state.ownsPager(replacement))
        assertFalse(state.ownsPager(first))
        assertEquals(0, state.currentPage)
        assertTrue(state.pages.isEmpty())
    }

    @Test fun temporarilyUnusablePanePreservesProgressButCannotMarkAnything() {
        val state = NewspaperViewportState()
        state.acceptSnapshot(Any(), listOf(article("a"), article("b")))
        state.updatePages(pages(listOf(0), listOf(1)), "portrait")
        present(state)
        state.suspendPresentation()
        assertFalse(state.turn(true, true) { throw AssertionError("Hidden content was marked") })
        assertEquals(0, state.currentPage)
        assertEquals(listOf("a"), displayed(state))
        present(state)
        assertTrue(state.turn(true, false) {})
    }

    @Test fun disposedFilterMeasurementCannotPublishOrPresentItsOldRows() {
        val state = NewspaperViewportState()
        val oldPager = Any()
        state.acceptSnapshot(oldPager, listOf(ArticleFlowItem.Date("Today", false), article("old")))
        val oldSource = state.items
        val oldRevision = state.revision
        val oldPages = pages(listOf(1))
        state.updatePages(oldPages, "same-geometry", oldSource, oldRevision)
        state.presented(listOf(1), oldSource, oldRevision)

        state.acceptSnapshot(Any(), emptyList())

        assertFalse(state.isCurrentSnapshot(oldSource, oldRevision))
        assertFalse(state.updatePages(oldPages, "same-geometry", oldSource, oldRevision))
        assertFalse(state.presented(listOf(1), oldSource, oldRevision))
        assertTrue(state.items.isEmpty())
        assertTrue(state.pages.isEmpty())
        assertFalse(state.turn(true, true) { throw AssertionError("Disposed filter was marked") })
        // A surviving old row still indexes its own immutable source, not the new empty list.
        assertEquals("old", (oldSource[1] as ArticleFlowItem.Article).articleWithFeed.article.id)
    }

    @Test fun sameSizedReplacementAndAppendInvalidateStaleMeasurementOwnership() {
        val state = NewspaperViewportState()
        val oldPager = Any()
        state.acceptSnapshot(oldPager, listOf(article("old", 2)))
        val oldSource = state.items
        val oldRevision = state.revision
        val replacementPager = Any()
        state.acceptSnapshot(replacementPager, listOf(article("replacement", 2)))
        assertFalse(state.isCurrentSnapshot(oldSource, oldRevision))
        val replacementSource = state.items
        val replacementRevision = state.revision
        assertTrue(state.updatePages(pages(listOf(0)), "portrait", replacementSource, replacementRevision))
        assertTrue(state.presented(listOf(0), replacementSource, replacementRevision))
        state.acceptSnapshot(replacementPager, listOf(article("replacement", 2), article("tail", 1)))
        assertFalse(state.updatePages(pages(listOf(0)), "portrait", replacementSource, replacementRevision))
        assertFalse(state.presented(listOf(0), replacementSource, replacementRevision))
        assertEquals(listOf("replacement", "tail"), ids(state))
    }

}
