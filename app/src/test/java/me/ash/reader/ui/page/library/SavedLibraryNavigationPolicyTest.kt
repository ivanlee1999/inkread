package me.ash.reader.ui.page.library

import me.ash.reader.ui.page.nav3.navigateAppBack
import me.ash.reader.ui.page.nav3.showRetainedDestination
import org.junit.Assert.*
import org.junit.Test

class SavedLibraryNavigationPolicyTest {
    @Test fun readerBackReturnsToSameRetainedLibraryEntry() {
        val stack = mutableListOf("feeds", "saved", "saved-reader:id")
        navigateAppBack(stack, "saved-reader:id", "feeds")
        assertEquals(listOf("feeds", "saved"), stack)
    }
    @Test fun repeatedSavedDestinationDoesNotDuplicateOrResetIt() {
        val stack = mutableListOf("feeds", "saved", "saved-reader:id")
        showRetainedDestination(stack, "saved")
        showRetainedDestination(stack, "saved")
        assertEquals(listOf("feeds", "saved"), stack)
    }
    @Test fun staleReaderBackDoesNotDismissNewCaptureDestination() {
        val stack = mutableListOf("feeds", "saved", "saved-reader:id", "settings")
        navigateAppBack(stack, "saved-reader:id", "feeds")
        assertEquals("settings", stack.last())
    }
    @Test fun subscriptionReturnsToOriginalFeedsWithoutChangingFavoritesRoute() {
        val stack = mutableListOf("feeds", "rss-favorites", "saved")
        showRetainedDestination(stack, "feeds")
        assertEquals(listOf("feeds"), stack)
    }
    @Test fun newSavedDestinationLeavesExistingRssBackHistoryIntact() {
        val stack = mutableListOf("feeds", "rss-reader:123")
        showRetainedDestination(stack, "saved")
        assertEquals(listOf("feeds", "rss-reader:123", "saved"), stack)
    }
}
