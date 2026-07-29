package me.ash.reader.ui.page.home.reading

import me.ash.reader.infrastructure.android.VolumeKeyEvent

/**
 * Where a freshly paginated article should open.
 *
 * [Start] is the default. [End] is used when the reader walks backwards into
 * the previous article, which should open on its last page the way a physical
 * book does. [Fraction] restores a previously saved reading position and is
 * resolution-independent, so it survives font size changes and rotation.
 */
sealed interface EInkInitialPosition {
    data object Start : EInkInitialPosition

    data object End : EInkInitialPosition

    /** Progress through the article in `[0, 1)`, i.e. `currentPage / totalPages`. */
    data class Fraction(val value: Float) : EInkInitialPosition
}

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

    /** Where the *current* content should open once pagination reports ready. */
    var initialPosition: EInkInitialPosition = EInkInitialPosition.Start
        private set

    /** Carried across exactly one [onContentReset], for cross-article navigation. */
    private var pendingForNextContent: EInkInitialPosition? = null

    private val pendingQueue = ArrayDeque<VolumeKeyEvent>(MAX_PENDING)

    /**
     * Progress through the article as a fraction in `[0, 1)`. Stable across
     * repagination, so it is what gets persisted and restored.
     */
    val progressFraction: Float
        get() = if (totalPages > 0) currentPage.toFloat() / totalPages else 0f

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
     * Sets page count, jumps to [initialPosition], marks ready, and flushes any
     * queued navigation events.
     */
    fun onPaginationReady(pages: Int) {
        totalPages = maxOf(1, pages)
        currentPage = resolvePage(initialPosition, totalPages)
        initialPosition = EInkInitialPosition.Start
        isReady = true
        if (currentPage != 0) onApplyPageToWebView(currentPage)
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
     * Called when the WebView was re-laid-out (rotation, window resize, split
     * screen) and JS recomputed the page stride from scratch. The reading
     * position is remapped by fraction and re-applied, because the old page
     * index no longer refers to the same content.
     */
    fun onRepaginated(pages: Int) {
        val fraction = progressFraction
        totalPages = maxOf(1, pages)
        currentPage = resolvePage(EInkInitialPosition.Fraction(fraction), totalPages)
        onApplyPageToWebView(currentPage)
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
        initialPosition = pendingForNextContent ?: EInkInitialPosition.Start
        pendingForNextContent = null
    }

    /**
     * Restore a previously saved reading position for the content about to be
     * paginated. Deliberately does not override an explicit request (such as
     * "open at the end" from backwards article navigation), so callers can
     * apply it unconditionally after [onContentReset].
     */
    fun restoreInitialPosition(fraction: Float) {
        if (initialPosition == EInkInitialPosition.Start && fraction > 0f) {
            initialPosition = EInkInitialPosition.Fraction(fraction)
        }
    }

    /**
     * Force ready after timeout (safety net). Only acts if not already ready.
     * Flushes pending events exactly once.
     */
    fun forceReady() {
        if (!isReady) {
            isReady = true
            if (totalPages == 0) totalPages = 1
            initialPosition = EInkInitialPosition.Start
            flushPending()
        }
    }

    /**
     * Directly set the current page (e.g. restoring saved state).
     */
    fun setCurrentPage(page: Int) {
        currentPage = page.coerceIn(0, maxOf(0, totalPages - 1))
    }

    private fun resolvePage(position: EInkInitialPosition, total: Int): Int = when (position) {
        EInkInitialPosition.Start -> 0
        EInkInitialPosition.End -> total - 1
        is EInkInitialPosition.Fraction ->
            (position.value * total).toInt().coerceIn(0, total - 1)
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
            pendingForNextContent = EInkInitialPosition.Start
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
            // Reading backwards: the previous article should open on its last
            // page so page turns stay continuous in both directions.
            pendingForNextContent = EInkInitialPosition.End
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
