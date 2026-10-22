package me.ash.reader.ui.page.home.reading

import me.ash.reader.ui.page.home.reading.EInkReaderTouchArbitrator.Action
import me.ash.reader.ui.page.home.reading.EInkReaderTouchArbitrator.Dispatch
import org.junit.Assert.*
import org.junit.Test

class EInkReaderTouchArbitratorTest {
    private fun recognizer() = EInkReaderTouchArbitrator(8f, 56f, 500)
    private fun EInkReaderTouchArbitrator.start(x: Float = 240f, y: Float = 200f,
        enabled: Boolean = true, selecting: Boolean = false, left: Float = 0f, right: Float = 0f) =
        down(x, y, 1_000, 400f, 800f, enabled, selecting, left, right)

    @Test fun downAndShortTapRemainNativeUntilRelease() {
        val t = recognizer()
        assertEquals(Dispatch.WEBVIEW, t.start().dispatch)
        assertEquals(Action.NEXT_PAGE, t.up(240f, 200f, 1_100).action)
    }
    @Test fun leftFortyPercentIsPreviousPage() {
        val t = recognizer(); t.start(159f)
        assertEquals(Action.PREVIOUS_PAGE, t.up(159f, 200f, 1_100).action)
    }
    @Test fun fortyPercentBoundaryIsNextPage() {
        val t = recognizer(); t.start(160f)
        assertEquals(Action.NEXT_PAGE, t.up(160f, 200f, 1_100).action)
    }
    @Test fun bottomFifteenPercentTogglesInsteadOfPaging() {
        val t = recognizer(); t.start(20f, 680f)
        assertEquals(Action.TOGGLE_CONTROLS, t.up(20f, 680f, 1_100).action)
    }
    @Test fun heldFingerOnTextOrBlankMarginNeverTurnsPage() {
        val t = recognizer(); t.start()
        val release = t.up(240f, 200f, 1_500)
        assertEquals(Dispatch.WEBVIEW, release.dispatch)
        assertNull(release.action)
        val margin = recognizer(); margin.start(20f, 700f)
        assertNull(margin.up(20f, 700f, 1_800).action)
    }
    @Test fun longPressThenDragBelongsToWebView() {
        val t = recognizer(); t.start()
        assertEquals(Dispatch.WEBVIEW, t.move(100f, 205f, 1_600).dispatch)
        val up = t.up(100f, 205f, 1_700)
        assertEquals(Dispatch.WEBVIEW, up.dispatch); assertNull(up.action)
    }
    @Test fun nativeActionModeImmediatelyRevokesNavigation() {
        val t = recognizer(); t.start(); t.claimSelection()
        assertNull(t.up(240f, 200f, 1_100).action)
        assertEquals(Dispatch.WEBVIEW, t.move(100f, 200f, 1_200).dispatch)
    }
    @Test fun selectedOutsideTapIsDismissOnlyEvenIfNativeSelectionEndsBeforeUp() {
        val t = recognizer(); t.start(selecting = true)
        assertEquals(Action.DISMISS_SELECTION, t.up(240f, 200f, 1_100).action)
    }
    @Test fun selectedBottomTapAlsoOnlyDismisses() {
        val t = recognizer(); t.start(240f, 730f, selecting = true)
        assertEquals(Action.DISMISS_SELECTION, t.up(240f, 730f, 1_100).action)
    }
    @Test fun selectionDragNeverDismissesOrNavigates() {
        val t = recognizer(); t.start(selecting = true)
        assertEquals(Dispatch.WEBVIEW, t.move(100f, 215f, 1_100).dispatch)
        assertNull(t.up(100f, 215f, 1_200).action)
    }
    @Test fun leftSwipeGoesToNextArticleOnlyOnUp() {
        val t = recognizer(); t.start()
        val lock = t.move(200f, 202f, 1_080)
        assertEquals(Dispatch.CANCEL_WEBVIEW, lock.dispatch); assertNull(lock.action)
        assertFalse(t.permitsNativeSelection)
        assertEquals(Dispatch.CONSUME, t.move(160f, 204f, 1_120).dispatch)
        assertEquals(Action.NEXT_ARTICLE, t.up(160f, 204f, 1_150).action)
    }
    @Test fun rightSwipeGoesToPreviousArticle() {
        val t = recognizer(); t.start(100f)
        assertEquals(Action.PREVIOUS_ARTICLE, t.up(190f, 205f, 1_150).action)
    }
    @Test fun subthresholdSwipeCancelsTapButDoesNotChangeArticle() {
        val t = recognizer(); t.start()
        assertEquals(Dispatch.CANCEL_WEBVIEW, t.move(220f, 200f, 1_050).dispatch)
        assertNull(t.up(190f, 200f, 1_150).action)
    }
    @Test fun exactArticleThresholdDoesNotTurn() {
        val t = recognizer(); t.start()
        assertNull(t.up(184f, 200f, 1_150).action)
    }
    @Test fun verticalDragIsStickyAndNeverBecomesTapOrArticleSwipe() {
        val t = recognizer(); t.start()
        assertEquals(Dispatch.CANCEL_WEBVIEW, t.move(242f, 220f, 1_050).dispatch)
        assertEquals(Dispatch.CONSUME, t.move(100f, 200f, 1_100).dispatch)
        assertNull(t.up(100f, 200f, 1_200).action)
    }
    @Test fun diagonalMotionMustPassAxisDominance() {
        val t = recognizer(); t.start()
        assertEquals(Dispatch.WEBVIEW, t.move(180f, 145f, 1_100).dispatch)
        assertNull(t.up(180f, 145f, 1_150).action)
    }
    @Test fun excursionAndReturnCannotBecomeATap() {
        val t = recognizer(); t.start()
        t.move(230f, 191f, 1_050)
        assertNull(t.up(240f, 200f, 1_150).action)
    }
    @Test fun systemGestureEdgesNeverPageOrSwitchArticle() {
        val left = recognizer(); left.start(10f, left = 20f)
        assertNull(left.up(100f, 200f, 1_150).action)
        val right = recognizer(); right.start(380f, right = 20f)
        assertNull(right.up(280f, 200f, 1_150).action)
    }
    @Test fun cancellationInvalidatesWholeStreamUntilNewDown() {
        val t = recognizer(); t.start(); t.move(100f, 200f, 1_080)
        t.cancel() // CANCEL, second pointer, identity/viewport change use this path.
        assertNull(t.up(100f, 200f, 1_150).action)
        t.start()
        assertTrue(t.permitsNativeSelection)
        assertEquals(Action.NEXT_PAGE, t.up(240f, 200f, 1_150).action)
    }
    @Test fun disabledNavigationNeverClaimsNativeGesture() {
        val t = recognizer(); t.start(enabled = false)
        assertEquals(Dispatch.WEBVIEW, t.move(100f, 200f, 1_080).dispatch)
        assertNull(t.up(100f, 200f, 1_150).action)
    }
    @Test fun noActionOnReleaseOutsideViewport() {
        val t = recognizer(); t.start()
        assertNull(t.up(500f, 200f, 1_150).action)
    }
    @Test fun earlyAxisLockCanFinishAfterLongPressDeadline() {
        val t = recognizer(); t.start(); t.move(200f, 200f, 1_100)
        assertEquals(Action.NEXT_ARTICLE, t.up(100f, 200f, 1_700).action)
    }
}
