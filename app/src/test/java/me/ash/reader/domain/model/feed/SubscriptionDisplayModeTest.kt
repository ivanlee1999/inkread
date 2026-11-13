package me.ash.reader.domain.model.feed

import org.junit.Assert.*
import org.junit.Test

class SubscriptionDisplayModeTest {
    private val feed = Feed("1:feed", "Feed", url = "https://example.com/rss", groupId = "1:group", accountId = 1)

    @Test fun legacyAndUnknownModesPreserveOldFullContentPresets() {
        assertNull(feed.selectedDisplayMode)
        assertFalse(feed.usesLocalFullContent)
        assertTrue(feed.copy(isFullContent = true).usesLocalFullContent)
        assertTrue(feed.copy(isFullContent = true, displayMode = "future-mode").usesLocalFullContent)
        assertTrue(feed.copy(isBrowser = true).isBrowser)
    }

    @Test fun explicitModeTakesPrecedenceAndNewsletterNeverEnablesPublisherExtraction() {
        assertTrue(feed.copy(displayMode = "local").usesLocalFullContent)
        assertFalse(feed.copy(displayMode = "server", isFullContent = true).usesLocalFullContent)
        assertFalse(feed.copy(displayMode = "original", isFullContent = true).usesLocalFullContent)
        assertFalse(feed.copy(displayMode = "local", isFullContent = true, isNewsletter = true).usesLocalFullContent)
        SubscriptionDisplayMode.entries.forEach { assertEquals(it, SubscriptionDisplayMode.fromStored(it.value)) }
    }
}
