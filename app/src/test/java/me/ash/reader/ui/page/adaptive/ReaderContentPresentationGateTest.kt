package me.ash.reader.ui.page.adaptive

import org.junit.Assert.*
import org.junit.Test

class ReaderContentPresentationGateTest {
    private data class Content(val body: String?, val status: String = "ready")
    private val identity = 1 to "article"
    private fun gate() = ReaderContentPresentationGate<Pair<Int, String>, Content> { it.body }

    @Test fun completionBeforeRecompositionStillKeepsTheDocumentWhereSelectionStarted() {
        val gate = gate()
        val mounted = Content("Feed body")
        assertEquals(mounted, gate.present(identity, mounted))
        val completedBeforeNativeCallback = Content("New full article")
        gate.onInteractionStarted()
        assertEquals(mounted, gate.present(identity, completedBeforeNativeCallback))
    }

    @Test fun selectionBeforeCompletionKeepsBodyThroughLaterCaptureAndEditHandoffs() {
        val gate = gate()
        val mounted = Content("Feed body")
        gate.present(identity, mounted)
        gate.onInteractionStarted() // Native selection.
        assertEquals(mounted, gate.present(identity, Content("Extracted body")))
        gate.onInteractionStarted() // Capture can outlive native ActionMode.
        gate.onInteractionStarted() // Then an annotation editor takes ownership.
        assertEquals(mounted, gate.present(identity, Content("Even later extraction")))
    }

    @Test fun endingSelectionDoesNotAutomaticallyReplaceTheDocument() {
        val gate = gate()
        val mounted = Content("Feed body")
        gate.present(identity, mounted)
        gate.onInteractionStarted()
        repeat(3) { assertEquals(mounted, gate.present(identity, Content("Fetched body"))) }
        assertTrue(gate.onExplicitSourceChoice { true })
        assertEquals(Content("Fetched body"), gate.present(identity, Content("Fetched body")))
    }

    @Test fun sourceChoiceRechecksLiveOwnershipAndCannotUnpinAStillOwnedDocument() {
        val gate = gate()
        val mounted = Content("Feed body")
        gate.present(identity, mounted)
        gate.onInteractionStarted()
        var idle = true // Dialog was opened while idle.
        val guard = { idle }
        idle = false // Native capture or formatting begins before its choice is clicked.
        assertFalse(gate.onExplicitSourceChoice(guard))
        assertEquals(mounted, gate.present(identity, Content("Fetched body")))
        idle = true
        assertTrue(gate.onExplicitSourceChoice(guard))
        assertEquals(Content("Fetched body"), gate.present(identity, Content("Fetched body")))
    }

    @Test fun sameBodyFailureNoticeCanChangeWithoutReleasingThePin() {
        val gate = gate()
        gate.present(identity, Content("Offline body"))
        gate.onInteractionStarted()
        val notice = Content("Offline body", "refresh failed")
        assertEquals(notice, gate.present(identity, notice))
        assertEquals(notice, gate.present(identity, Content("Different body")))
    }

    @Test fun accountOrArticleChangeClearsTheOldPin() {
        for (nextIdentity in listOf(2 to "article", 1 to "another article")) {
            val gate = gate()
            gate.present(identity, Content("Old body"))
            gate.onInteractionStarted()
            val next = Content("New reader body")
            assertEquals(next, gate.present(nextIdentity, next))
            assertEquals(Content("New reader full body"), gate.present(nextIdentity, Content("New reader full body")))
        }
    }

    @Test fun loadingDoesNotPinAnArticleBeforeItHasReadableContent() {
        val gate = gate()
        gate.present(identity, Content(null, "loading"))
        gate.onInteractionStarted()
        assertEquals(Content("First readable body"), gate.present(identity, Content("First readable body")))
    }

    @Test fun aNewInteractionAfterExplicitChoicePinsTheNewlyPresentedBody() {
        val gate = gate()
        gate.present(identity, Content("Feed body"))
        gate.onInteractionStarted()
        gate.onExplicitSourceChoice { true }
        val newMounted = Content("Full body")
        gate.present(identity, newMounted)
        gate.onInteractionStarted()
        assertEquals(newMounted, gate.present(identity, Content("Refreshed body")))
    }

    @Test fun returnToSameArticleAfterAnotherIdentityDoesNotRestoreAnOldPin() {
        val gate = gate()
        gate.present(identity, Content("Original body"))
        gate.onInteractionStarted()
        gate.present(1 to "other article", Content("Other body"))
        assertEquals(Content("Fresh body"), gate.present(identity, Content("Fresh body")))
    }
}
