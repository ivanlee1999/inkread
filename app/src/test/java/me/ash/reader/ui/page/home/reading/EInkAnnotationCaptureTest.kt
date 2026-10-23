package me.ash.reader.ui.page.home.reading

import org.junit.Assert.*
import org.junit.Test

class EInkAnnotationCaptureTest {
    @Test fun replacementBeforeResultPreservesTheCommittedCommand() {
        val capture = EInkAnnotationCapture<Any>()
        val modes = EInkSelectionActionModeLifecycle<Any>()
        val view = Any(); val original = Any(); val replacement = Any()
        modes.start(original)
        val ticket = requireNotNull(capture.begin("document", view, addNote = false))
        val dismissal = requireNotNull(modes.destroyed(original))
        capture.onEvent(EInkAnnotationCapture.Event.NATIVE_MODE_ENDED)
        modes.start(replacement)
        assertFalse(modes.completeDismissal(dismissal, sameReader = true, attached = true))
        assertTrue(capture.hasPending)
        assertTrue(capture.consume(ticket, "document", view, blocked = false))
        assertFalse(ticket.addNote)
        assertFalse(capture.consume(ticket, "document", view, blocked = false))
    }

    @Test fun nativeDismissalBeforeResultRetainsPendingInputOwnership() {
        val capture = EInkAnnotationCapture<Any>()
        val modes = EInkSelectionActionModeLifecycle<Any>()
        val view = Any(); val mode = Any()
        modes.start(mode)
        val ticket = requireNotNull(capture.begin("document", view, addNote = true))
        val dismissal = requireNotNull(modes.destroyed(mode))
        capture.onEvent(EInkAnnotationCapture.Event.NATIVE_MODE_ENDED)
        assertTrue(modes.completeDismissal(dismissal, sameReader = true, attached = true))
        assertFalse(modes.ownsSelection)
        assertTrue("Pending capture still owns Back and navigation", capture.hasPending)
        assertTrue(capture.consume(ticket, "document", view, blocked = false))
        assertTrue(ticket.addNote)
    }

    @Test fun resultBeforeNativeDismissalCannotBeConsumedTwice() {
        val capture = EInkAnnotationCapture<Any>()
        val view = Any()
        val ticket = requireNotNull(capture.begin("document", view, addNote = false))
        assertTrue(capture.consume(ticket, "document", view, blocked = false))
        capture.onEvent(EInkAnnotationCapture.Event.NATIVE_MODE_ENDED)
        assertFalse(capture.hasPending)
        assertFalse(capture.consume(ticket, "document", view, blocked = false))
    }

    @Test fun realCancellationBeforeResultRevokesEachCommittedCommand() {
        for (event in EInkAnnotationCapture.Event.entries.filter { it != EInkAnnotationCapture.Event.NATIVE_MODE_ENDED }) {
            val capture = EInkAnnotationCapture<Any>()
            val view = Any()
            val ticket = requireNotNull(capture.begin("document", view, addNote = true))
            capture.onEvent(EInkAnnotationCapture.Event.NATIVE_MODE_ENDED)
            capture.onEvent(event)
            assertFalse(event.name, capture.hasPending)
            assertFalse(event.name, capture.consume(ticket, "document", view, blocked = false))
        }
    }

    @Test fun readerDownCancelsButItsReleaseRemainsDismissalOnly() {
        val capture = EInkAnnotationCapture<Any>()
        val view = Any()
        val ticket = requireNotNull(capture.begin("document", view, addNote = false))
        capture.onEvent(EInkAnnotationCapture.Event.NATIVE_MODE_ENDED)
        val ownedAtDown = capture.hasPending
        capture.onEvent(EInkAnnotationCapture.Event.READER_DOWN)
        val touch = EInkReaderTouchArbitrator(8f, 56f, 500)
        touch.down(800f, 300f, 0, 1000f, 800f, navigationEnabled = !capture.hasPending,
            selectionActive = ownedAtDown, leftEdge = 0f, rightEdge = 0f)
        assertEquals(EInkReaderTouchArbitrator.Action.DISMISS_SELECTION, touch.up(800f, 300f, 50).action)
        assertFalse(capture.consume(ticket, "document", view, blocked = false))
    }

    @Test fun duplicateClickCannotReplaceTheCommittedAction() {
        val capture = EInkAnnotationCapture<Any>()
        val view = Any()
        val ticket = requireNotNull(capture.begin("document", view, addNote = false))
        assertNull(capture.begin("document", view, addNote = true))
        assertTrue(capture.consume(ticket, "document", view, blocked = false))
        assertFalse(ticket.addNote)
    }

    @Test fun oldCallbackCannotConsumeOrCancelANewerTicket() {
        val capture = EInkAnnotationCapture<Any>()
        val view = Any()
        val old = requireNotNull(capture.begin("document", view, addNote = false))
        capture.onEvent(EInkAnnotationCapture.Event.EXPLICIT_DISMISSAL)
        val current = requireNotNull(capture.begin("document", view, addNote = true))
        assertFalse(capture.consume(old, "document", view, blocked = false))
        assertTrue(capture.hasPending)
        assertTrue(capture.consume(current, "document", view, blocked = false))
        assertTrue(current.addNote)
    }

    @Test fun generationViewAndModalFencesRejectResultsEvenWithoutPriorCancellation() {
        data class EqualView(val id: Int)
        val view = EqualView(1)
        val mismatches = listOf(Triple("new-document", view, false),
            Triple("document", EqualView(1), false), Triple("document", null, false),
            Triple("document", view, true))
        for ((generation, currentView, blocked) in mismatches) {
            val capture = EInkAnnotationCapture<EqualView>()
            val ticket = requireNotNull(capture.begin("document", view, addNote = false))
            assertFalse(capture.consume(ticket, generation, currentView, blocked))
            assertFalse(capture.hasPending)
            assertFalse(capture.consume(ticket, "document", view, blocked = false))
        }
    }

    @Test fun timeoutAndResultCanOnlyWinOnceInEitherOrder() {
        for (timeoutFirst in listOf(true, false)) {
            val capture = EInkAnnotationCapture<Any>()
            val view = Any()
            val ticket = requireNotNull(capture.begin("document", view, addNote = false))
            val effects = mutableListOf<String>()
            for (effect in if (timeoutFirst) listOf("timeout", "create") else listOf("create", "timeout")) {
                if (capture.consume(ticket, "document", view, blocked = false)) effects += effect
            }
            assertEquals(listOf(if (timeoutFirst) "timeout" else "create"), effects)
        }
    }
}
