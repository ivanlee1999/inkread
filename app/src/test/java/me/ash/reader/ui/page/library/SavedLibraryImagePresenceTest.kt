package me.ash.reader.ui.page.library

import org.junit.Assert.*
import org.junit.Test

class SavedLibraryImagePresenceTest {
    @Test fun pendingImageArrivalDoesNotAddPhotoGeometryToPrintedEdition() {
        val edition = SavedLibraryImagePresence()
        assertFalse(edition.reserveImage("pending-article", null))
        repeat(4) { assertFalse(edition.reserveImage("pending-article", "/saved/retained-photo.jpg")) }
    }

    @Test fun reopenedEditionUsesLatestRetainedPhotoWithoutReservingPendingBlankBoxes() {
        val original = SavedLibraryImagePresence()
        assertFalse(original.reserveImage("article", null))
        assertFalse(original.reserveImage("article", "/saved/retained-photo.jpg"))
        val reopened = SavedLibraryImagePresence()
        assertTrue(reopened.reserveImage("article", "/saved/retained-photo.jpg"))
        assertFalse(reopened.reserveImage("text-only", null))
        assertFalse(reopened.reserveImage("blank-path", "  "))
    }

    @Test fun existingPhotoGeometryStaysStableDuringRetryOrReplacement() {
        val edition = SavedLibraryImagePresence()
        assertTrue(edition.reserveImage("article", "/saved/old.jpg"))
        assertTrue(edition.reserveImage("article", null))
        assertTrue(edition.reserveImage("article", "/saved/new.jpg"))
        // A later edition can recover to text-only if the cached image was actually removed.
        assertFalse(SavedLibraryImagePresence().reserveImage("article", null))
    }

    @Test fun imagePresenceIsPinnedPerIdentityNotPerPagePosition() {
        val edition = SavedLibraryImagePresence()
        assertFalse(edition.reserveImage("first", null))
        assertTrue(edition.reserveImage("second", "/saved/second.jpg"))
        assertTrue(edition.reserveImage("second", "/saved/replacement.jpg"))
        assertFalse(edition.reserveImage("first", "/saved/late.jpg"))
    }
}
