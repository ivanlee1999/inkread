package me.ash.reader.ui.page.home.reading

import kotlin.math.abs

/**
 * A bounded recognizer, not a replacement for WebView's text selection. DOWN and
 * undecided motion belong to WebView. Only a pre-long-press drag claims/cancels
 * that stream; a held finger can never become a page tap on release.
 * Synchronize the bounded state only: layout may revoke it off the UI thread.
 */
internal class EInkReaderTouchArbitrator(
    private val touchSlop: Float,
    private val articleThreshold: Float,
    private val longPressTimeoutMillis: Long,
) {
    enum class Action { PREVIOUS_PAGE, NEXT_PAGE, TOGGLE_CONTROLS, PREVIOUS_ARTICLE, NEXT_ARTICLE, DISMISS_SELECTION }
    enum class Dispatch { WEBVIEW, CANCEL_WEBVIEW, CONSUME }
    data class Result(val dispatch: Dispatch = Dispatch.WEBVIEW, val action: Action? = null)

    private var active = false
    private var navigationAllowed = false
    private var selectionAtDown = false
    private var selectionClaimed = false
    private var cancelledNative = false
    private var horizontal = false
    private var movedBeyondTap = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var width = 0f
    private var height = 0f

    val permitsNativeSelection: Boolean @Synchronized get() = !cancelledNative

    @Synchronized fun down(x: Float, y: Float, time: Long, width: Float, height: Float,
             navigationEnabled: Boolean, selectionActive: Boolean, leftEdge: Float, rightEdge: Float): Result {
        active = true
        downX = x; downY = y; downTime = time
        this.width = width; this.height = height
        selectionAtDown = selectionActive
        selectionClaimed = false
        cancelledNative = false
        horizontal = false
        movedBeyondTap = false
        navigationAllowed = navigationEnabled && !selectionActive && width > 0f && height > 0f &&
            x >= leftEdge && x < width - rightEdge && y >= 0f && y < height
        return Result()
    }

    /** ActionMode may start before Compose observes its new selection state. */
    @Synchronized fun claimSelection() {
        selectionClaimed = true
        navigationAllowed = false
    }

    @Synchronized fun cancel(): Result {
        active = false
        navigationAllowed = false
        horizontal = false
        val dispatch = if (cancelledNative) Dispatch.CONSUME else Dispatch.CANCEL_WEBVIEW
        cancelledNative = true
        return Result(dispatch)
    }

    @Synchronized fun move(x: Float, y: Float, time: Long): Result {
        if (!active) return Result(if (cancelledNative) Dispatch.CONSUME else Dispatch.WEBVIEW)
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy > touchSlop * touchSlop) movedBeyondTap = true
        if (selectionAtDown || selectionClaimed || !navigationAllowed) {
            return Result(if (cancelledNative) Dispatch.CONSUME else Dispatch.WEBVIEW)
        }
        if (!horizontal && time - downTime >= longPressTimeoutMillis) {
            claimSelection()
            return Result()
        }
        if (!horizontal && abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
            // Pagination has no vertical scrolling owner. A drag is not a tap.
            navigationAllowed = false
            cancelledNative = true
            return Result(Dispatch.CANCEL_WEBVIEW)
        }
        if (!horizontal && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
            horizontal = true
            cancelledNative = true
            return Result(Dispatch.CANCEL_WEBVIEW)
        }
        return Result(if (cancelledNative) Dispatch.CONSUME else Dispatch.WEBVIEW)
    }

    @Synchronized fun up(x: Float, y: Float, time: Long): Result {
        if (!active) return Result(if (cancelledNative) Dispatch.CONSUME else Dispatch.WEBVIEW)
        val motion = move(x, y, time)
        active = false
        val dx = x - downX
        val action = when {
            // A stream begun in selection remains dismiss-only even if native
            // ActionMode is destroyed before this UP reaches the application.
            selectionAtDown && !movedBeyondTap && time - downTime < longPressTimeoutMillis -> Action.DISMISS_SELECTION
            selectionClaimed || !navigationAllowed || x < 0f || x >= width || y < 0f || y >= height -> null
            horizontal && dx < -articleThreshold -> Action.NEXT_ARTICLE
            horizontal && dx > articleThreshold -> Action.PREVIOUS_ARTICLE
            horizontal || movedBeyondTap -> null
            y >= height * .85f -> Action.TOGGLE_CONTROLS
            x < width * .4f -> Action.PREVIOUS_PAGE
            else -> Action.NEXT_PAGE
        }
        return motion.copy(action = action)
    }
}
