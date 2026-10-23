package me.ash.reader.ui.page.home.reading

import org.junit.Assert.*
import org.junit.Test

class EInkSelectionActionModeLifecycleTest {
    @Test fun replacementPreservesOwnershipAndRejectsOldQueuedDismissal() {
        val lifecycle = EInkSelectionActionModeLifecycle<Any>()
        val first = Any(); val replacement = Any()
        lifecycle.start(first)
        val token = requireNotNull(lifecycle.destroyed(first))
        assertNull(lifecycle.activeMode)
        assertTrue("Navigation stays blocked between floating menus", lifecycle.ownsSelection)
        lifecycle.start(replacement)
        assertFalse(lifecycle.completeDismissal(token, sameReader = true, attached = true))
        assertSame(replacement, lifecycle.activeMode)
        assertTrue(lifecycle.ownsSelection)
    }

    @Test fun actualDismissalCompletesOnlyOnceOnNextQueueTurn() {
        val lifecycle = EInkSelectionActionModeLifecycle<Any>()
        val mode = Any(); lifecycle.start(mode)
        val token = requireNotNull(lifecycle.destroyed(mode))
        assertTrue(lifecycle.ownsSelection)
        assertTrue(lifecycle.completeDismissal(token, sameReader = true, attached = true))
        assertFalse(lifecycle.ownsSelection)
        assertFalse(lifecycle.completeDismissal(token, sameReader = true, attached = true))
    }

    @Test fun lateOldModeDestructionCannotTouchReplacement() {
        val lifecycle = EInkSelectionActionModeLifecycle<Any>()
        val old = Any(); val replacement = Any()
        lifecycle.start(old); lifecycle.start(replacement)
        assertNull(lifecycle.destroyed(old))
        assertSame(replacement, lifecycle.activeMode)
        assertTrue(lifecycle.ownsSelection)
    }

    @Test fun explicitDismissalImmediatelyInvalidatesQueuedCompletion() {
        val lifecycle = EInkSelectionActionModeLifecycle<Any>()
        val mode = Any(); lifecycle.start(mode)
        val token = requireNotNull(lifecycle.destroyed(mode))
        lifecycle.finish()
        assertFalse(lifecycle.ownsSelection)
        assertFalse(lifecycle.completeDismissal(token, sameReader = true, attached = true))
    }

    @Test fun explicitFinishBeforeNativeDestroyNeverSchedulesExternalDismissal() {
        val lifecycle = EInkSelectionActionModeLifecycle<Any>()
        val mode = Any(); lifecycle.start(mode); lifecycle.finish()
        assertNull(lifecycle.destroyed(mode))
        assertFalse(lifecycle.ownsSelection)
    }

    @Test fun readerIdentityAndAttachmentFenceCompletion() {
        val lifecycle = EInkSelectionActionModeLifecycle<Any>()
        val mode = Any(); lifecycle.start(mode)
        val token = requireNotNull(lifecycle.destroyed(mode))
        assertFalse(lifecycle.completeDismissal(token, sameReader = false, attached = true))
        assertFalse(lifecycle.completeDismissal(token, sameReader = true, attached = false))
        assertTrue(lifecycle.ownsSelection)
        lifecycle.finish() // Document replacement cancels the old operation.
        lifecycle.start(Any())
        assertFalse(lifecycle.completeDismissal(token, sameReader = true, attached = true))
        assertTrue(lifecycle.ownsSelection)
    }
}
