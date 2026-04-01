package me.ash.reader.ui.page.home.reading

import me.ash.reader.infrastructure.android.VolumeKeyEvent

/**
 * Pure-logic controller for e-ink paginated navigation.
 *
 * Separates page-state decisions from WebView/Compose concerns so the
 * navigation logic can be unit-tested without Android dependencies.
 *
 * Callers supply callbacks for side-effects (WebView JS calls, article
 * boundary navigation, haptic feedback, etc.).
 */
class EInkPageNavigationController(
    private val onPageChanged: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
    private val onApplyPageToWebView: (page: Int) -> Unit = {},
    private val onNextArticle: () -> Unit = {},
    private val onPrevArticle: () -> Unit = {},
    private val onBoundary: (message: String) -> Unit = {},
    private val onPageTurnFeedback: (direction: VolumeKeyEvent) -> Unit = {},
    private val hasNextArticle: () -> Boolean = { false },
    private val hasPrevArticle: () -> Boolean = { false },
) {
    var currentPage: Int = 0
        private set
    var totalPages: Int = 0
        private set
    var isReady: Boolean = false
        private set

    private val pendingQueue = ArrayDeque<VolumeKeyEvent>(MAX_PENDING)

    /**
     * Handle a volume key navigation event. If pagination is not yet ready
     * (loading / resetting), the event is queued and will be flushed once
     * [onPaginationReady] is called.
     */
    fun handleNavigation(event: VolumeKeyEvent) {
        if (!isReady && totalPages == 0) {
            enqueue(event)
            return
        }
        executeNavigation(event)
    }

    /**
     * Called when the JS bridge reports initial pagination is ready.
     * Sets page count, resets to page 0, marks ready, and flushes any
     * queued navigation events.
     */
    fun onPaginationReady(pages: Int) {
        totalPages = maxOf(1, pages)
        currentPage = 0
        isReady = true
        onPageChanged(currentPage + 1, totalPages)
        flushPending()
    }

    /**
     * Called when JS bridge reports an updated total page count (e.g. after
     * image reflow). Clamps currentPage if it exceeds the new total.
     */
    fun onTotalPagesUpdated(pages: Int) {
        totalPages = maxOf(1, pages)
        if (currentPage >= totalPages) {
            currentPage = totalPages - 1
            onApplyPageToWebView(currentPage)
        }
        onPageChanged(currentPage + 1, totalPages)
    }

    /**
     * Called when content/article changes. Resets all state and clears any
     * pending queued events to prevent stale navigation from carrying into
     * a different article.
     */
    fun onContentReset() {
        isReady = false
        currentPage = 0
        totalPages = 0
        pendingQueue.clear()
    }

    /**
     * Force ready after timeout (safety net). Only acts if not already ready.
     * Flushes pending events exactly once.
     */
    fun forceReady() {
        if (!isReady) {
            isReady = true
            if (totalPages == 0) totalPages = 1
            flushPending()
        }
    }

    /**
     * Directly set the current page (e.g. restoring saved state).
     */
    fun setCurrentPage(page: Int) {
        currentPage = page.coerceIn(0, maxOf(0, totalPages - 1))
    }

    private fun executeNavigation(event: VolumeKeyEvent) {
        when (event) {
            VolumeKeyEvent.NEXT -> performNextPage()
            VolumeKeyEvent.PREV -> performPrevPage()
        }
    }

    private fun performNextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++
            onApplyPageToWebView(currentPage)
            onPageChanged(currentPage + 1, totalPages)
            onPageTurnFeedback(VolumeKeyEvent.NEXT)
        } else if (hasNextArticle()) {
            onNextArticle()
        } else {
            onBoundary("No more articles")
        }
    }

    private fun performPrevPage() {
        if (currentPage > 0) {
            currentPage--
            onApplyPageToWebView(currentPage)
            onPageChanged(currentPage + 1, totalPages)
            onPageTurnFeedback(VolumeKeyEvent.PREV)
        } else if (hasPrevArticle()) {
            onPrevArticle()
        } else {
            onBoundary("No previous articles")
        }
    }

    private fun enqueue(event: VolumeKeyEvent) {
        if (pendingQueue.size < MAX_PENDING) {
            pendingQueue.addLast(event)
        }
    }

    private fun flushPending() {
        while (pendingQueue.isNotEmpty()) {
            executeNavigation(pendingQueue.removeFirst())
        }
    }

    companion object {
        /** Maximum queued events to prevent unbounded buildup. */
        const val MAX_PENDING = 8
    }
}
