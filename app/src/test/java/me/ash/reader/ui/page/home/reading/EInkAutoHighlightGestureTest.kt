package me.ash.reader.ui.page.home.reading

import org.junit.Assert.*
import org.junit.Test

class EInkAutoHighlightGestureTest {
    @Test fun offAndExistingSelectionNeverArmAnAutomaticSave() {
        val gate = EInkAutoHighlightGesture()
        assertNull(gate.down(enabled = false, selectionAlreadyOwned = false))
        assertNull(gate.selectionStarted())
        assertNull(gate.up())
        gate.finishSession()
        assertNull(gate.down(enabled = true, selectionAlreadyOwned = true))
        assertNull(gate.selectionStarted())
        assertNull(gate.up())
    }

    @Test fun selectionMustFinishAnUncancelledPointerGestureBeforeCapture() {
        val gate = EInkAutoHighlightGesture()
        val token = requireNotNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        assertNull(gate.selectionStarted())
        assertFalse(gate.consume(token)) // finger and initial handle are still moving
        assertEquals(token, gate.up())
        assertTrue(gate.consume(token))
        assertFalse(gate.consume(token))
    }

    @Test fun delayedNativeSelectionCanCompleteAfterUpButOnlyOnce() {
        val gate = EInkAutoHighlightGesture()
        val token = requireNotNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        assertNull(gate.up())
        assertEquals(token, gate.selectionStarted())
        assertNull(gate.selectionStarted()) // provider menu replacement
        assertTrue(gate.consume(token))
        assertNull(gate.selectionStarted())
    }

    @Test fun cancelMultitouchNavigationAndManualActionRevokeLateCapture() {
        val gate = EInkAutoHighlightGesture()
        val token = requireNotNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        gate.selectionStarted()
        gate.up()
        gate.cancelGesture()
        assertFalse(gate.consume(token))
        assertNull(gate.selectionStarted())
    }

    @Test fun newDownCancelsPendingSnapshotEvenIfItStartsInSelection() {
        val gate = EInkAutoHighlightGesture()
        val token = requireNotNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        gate.selectionStarted()
        gate.up()
        assertNull(gate.down(enabled = true, selectionAlreadyOwned = true))
        assertFalse(gate.consume(token))
        assertNull(gate.up())
    }

    @Test fun handleAdjustmentsAndReplacementCannotSaveTwiceInOneSession() {
        val gate = EInkAutoHighlightGesture()
        val token = requireNotNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        gate.selectionStarted()
        gate.up()
        assertTrue(gate.consume(token))
        // Native handles live in another PopupWindow; ActionMode may be replaced,
        // but neither that nor an ordinary reader DOWN starts another save.
        repeat(4) { assertNull(gate.selectionStarted()); assertNull(gate.up()) }
        assertNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        assertFalse(gate.consume(token))
    }

    @Test fun dismissalOrDocumentChangeInvalidatesOldTokenWithoutConsumingNewOne() {
        val gate = EInkAutoHighlightGesture()
        val old = requireNotNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        gate.selectionStarted(); gate.up(); gate.finishSession()
        val fresh = requireNotNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        gate.selectionStarted(); gate.up()
        assertFalse(gate.consume(old))
        assertTrue(gate.consume(fresh))
        assertNotEquals(old, fresh)
    }

    @Test fun ordinaryTapWithoutNativeSelectionNeverAuthorizesCapture() {
        val gate = EInkAutoHighlightGesture()
        val token = requireNotNull(gate.down(enabled = true, selectionAlreadyOwned = false))
        assertNull(gate.up())
        assertFalse(gate.consume(token))
    }
}
