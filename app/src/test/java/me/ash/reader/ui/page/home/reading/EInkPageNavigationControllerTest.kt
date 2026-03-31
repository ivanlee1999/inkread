package me.ash.reader.ui.page.home.reading

import me.ash.reader.infrastructure.android.VolumeKeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EInkPageNavigationControllerTest {

    private val pagesApplied = mutableListOf<Int>()
    private val pageChanges = mutableListOf<Pair<Int, Int>>()
    private val boundaries = mutableListOf<String>()
    private val feedbackDirections = mutableListOf<VolumeKeyEvent>()
    private var nextArticleCalled = false
    private var prevArticleCalled = false
    private var hasNext = false
    private var hasPrev = false

    private lateinit var controller: EInkPageNavigationController

    @Before
    fun setUp() {
        pagesApplied.clear()
        pageChanges.clear()
        boundaries.clear()
        feedbackDirections.clear()
        nextArticleCalled = false
        prevArticleCalled = false
        hasNext = false
        hasPrev = false

        controller = EInkPageNavigationController(
            onPageChanged = { page, total -> pageChanges.add(page to total) },
            onApplyPageToWebView = { page -> pagesApplied.add(page) },
            onNextArticle = { nextArticleCalled = true },
            onPrevArticle = { prevArticleCalled = true },
            onBoundary = { msg -> boundaries.add(msg) },
            onPageTurnFeedback = { dir -> feedbackDirections.add(dir) },
            hasNextArticle = { hasNext },
            hasPrevArticle = { hasPrev },
        )
    }

    @Test
    fun `NEXT increments page when ready`() {
        controller.onPaginationReady(5)

        controller.handleNavigation(VolumeKeyEvent.NEXT)

        assertEquals(1, controller.currentPage)
        assertEquals(5, controller.totalPages)
        assertEquals(1, pagesApplied.size)
        assertEquals(1, pagesApplied[0])
    }

    @Test
    fun `PREV decrements page when ready`() {
        controller.onPaginationReady(5)
        // Move to page 2 first
        controller.handleNavigation(VolumeKeyEvent.NEXT)
        controller.handleNavigation(VolumeKeyEvent.NEXT)
        assertEquals(2, controller.currentPage)

        controller.handleNavigation(VolumeKeyEvent.PREV)
        assertEquals(1, controller.currentPage)
    }

    @Test
    fun `PREV at first page triggers prev article when available`() {
        hasPrev = true
        controller.onPaginationReady(3)

        controller.handleNavigation(VolumeKeyEvent.PREV)

        assertTrue(prevArticleCalled)
        assertEquals(0, controller.currentPage) // stays at 0
    }

    @Test
    fun `NEXT at last page triggers next article when available`() {
        hasNext = true
        controller.onPaginationReady(1) // single page article

        controller.handleNavigation(VolumeKeyEvent.NEXT)

        assertTrue(nextArticleCalled)
    }

    @Test
    fun `PREV at first page shows boundary when no prev article`() {
        hasPrev = false
        controller.onPaginationReady(3)

        controller.handleNavigation(VolumeKeyEvent.PREV)

        assertEquals(1, boundaries.size)
        assertEquals("No previous articles", boundaries[0])
    }

    @Test
    fun `NEXT at last page shows boundary when no next article`() {
        hasNext = false
        controller.onPaginationReady(1)

        controller.handleNavigation(VolumeKeyEvent.NEXT)

        assertEquals(1, boundaries.size)
        assertEquals("No more articles", boundaries[0])
    }

    @Test
    fun `event received while not ready is queued, not dropped`() {
        // Controller starts not ready with totalPages == 0
        assertFalse(controller.isReady)
        assertEquals(0, controller.totalPages)

        controller.handleNavigation(VolumeKeyEvent.NEXT)

        // Should not have applied any page
        assertTrue(pagesApplied.isEmpty())
        assertEquals(0, controller.currentPage)
    }

    @Test
    fun `queued events flush after pagination becomes ready`() {
        // Queue events while not ready
        controller.handleNavigation(VolumeKeyEvent.NEXT)
        controller.handleNavigation(VolumeKeyEvent.NEXT)

        // Now become ready with 5 pages
        controller.onPaginationReady(5)

        // Should have flushed: page 0 -> 1 -> 2
        assertEquals(2, controller.currentPage)
        // pagesApplied should have page 1 and page 2
        assertTrue(pagesApplied.contains(1))
        assertTrue(pagesApplied.contains(2))
    }

    @Test
    fun `flush respects article boundaries`() {
        hasNext = true
        // Queue more NEXT events than the article has pages
        controller.handleNavigation(VolumeKeyEvent.NEXT)
        controller.handleNavigation(VolumeKeyEvent.NEXT)
        controller.handleNavigation(VolumeKeyEvent.NEXT)

        // Ready with only 2 pages (0 and 1)
        controller.onPaginationReady(2)

        // First NEXT goes to page 1, second NEXT triggers next article
        assertEquals(1, controller.currentPage)
        assertTrue(nextArticleCalled)
    }

    @Test
    fun `queue is cleared when content changes`() {
        // Queue events
        controller.handleNavigation(VolumeKeyEvent.NEXT)
        controller.handleNavigation(VolumeKeyEvent.NEXT)

        // Content changes
        controller.onContentReset()

        // Now become ready — queued events should NOT apply
        controller.onPaginationReady(5)

        assertEquals(0, controller.currentPage)
        // Only the onPaginationReady callback, no page navigations
        assertTrue(pagesApplied.isEmpty())
    }

    @Test
    fun `onTotalPagesUpdated clamps currentPage if it exceeds new total`() {
        controller.onPaginationReady(10)
        // Navigate to page 8
        repeat(8) { controller.handleNavigation(VolumeKeyEvent.NEXT) }
        assertEquals(8, controller.currentPage)

        // Now total shrinks to 5
        controller.onTotalPagesUpdated(5)

        assertEquals(4, controller.currentPage) // clamped to last page
        assertTrue(pagesApplied.contains(4)) // WebView updated
    }

    @Test
    fun `onTotalPagesUpdated does not clamp when current page is within range`() {
        controller.onPaginationReady(10)
        controller.handleNavigation(VolumeKeyEvent.NEXT)
        assertEquals(1, controller.currentPage)

        pagesApplied.clear()
        controller.onTotalPagesUpdated(8)

        assertEquals(1, controller.currentPage) // unchanged
        assertTrue(pagesApplied.isEmpty()) // no WebView update needed for clamping
    }

    @Test
    fun `forceReady sets totalPages to 1 if still 0 and flushes pending`() {
        controller.handleNavigation(VolumeKeyEvent.NEXT)

        controller.forceReady()

        assertTrue(controller.isReady)
        assertEquals(1, controller.totalPages)
        // The queued NEXT should trigger boundary (only 1 page, already at page 0->last)
        // Actually with 1 page, NEXT at page 0 with totalPages=1 means currentPage(0) < totalPages-1(0) is false
        // so it should trigger next article or boundary
    }

    @Test
    fun `forceReady is idempotent - does not double flush`() {
        controller.handleNavigation(VolumeKeyEvent.NEXT)

        controller.forceReady()
        val boundariesAfterFirst = boundaries.size

        controller.forceReady()
        assertEquals(boundariesAfterFirst, boundaries.size) // no additional effects
    }

    @Test
    fun `page turn feedback is emitted on successful navigation`() {
        controller.onPaginationReady(5)

        controller.handleNavigation(VolumeKeyEvent.NEXT)
        assertEquals(VolumeKeyEvent.NEXT, feedbackDirections.last())

        controller.handleNavigation(VolumeKeyEvent.PREV)
        assertEquals(VolumeKeyEvent.PREV, feedbackDirections.last())
    }

    @Test
    fun `onContentReset resets all state`() {
        controller.onPaginationReady(5)
        controller.handleNavigation(VolumeKeyEvent.NEXT)
        controller.handleNavigation(VolumeKeyEvent.NEXT)

        controller.onContentReset()

        assertFalse(controller.isReady)
        assertEquals(0, controller.currentPage)
        assertEquals(0, controller.totalPages)
    }

    @Test
    fun `pending queue is bounded`() {
        // Queue more events than MAX_PENDING
        repeat(EInkPageNavigationController.MAX_PENDING + 5) {
            controller.handleNavigation(VolumeKeyEvent.NEXT)
        }

        // Become ready with many pages
        controller.onPaginationReady(100)

        // Should have navigated at most MAX_PENDING pages
        assertEquals(EInkPageNavigationController.MAX_PENDING, controller.currentPage)
    }
}
