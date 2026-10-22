package me.ash.reader.ui.page.library

import org.junit.Assert.*
import org.junit.Test

class LinkCapturePolicyTest {
    @Test fun genericTextShareAlwaysAsksSaveOrSubscribe() {
        assertEquals(LinkCaptureMode.CHOOSE, externalLinkCaptureMode("android.intent.action.SEND", "text/plain"))
        assertEquals(LinkCaptureMode.CHOOSE, externalLinkCaptureMode("android.intent.action.SEND", "application/rss+xml"))
    }
    @Test fun typedFeedViewPreservesSubscriptionRoute() {
        listOf("application/rss+xml", "application/atom+xml", "text/xml", "application/xml", "application/json", "application/rss+xml; charset=UTF-8").forEach {
            assertEquals(LinkCaptureMode.SUBSCRIBE, externalLinkCaptureMode("android.intent.action.VIEW", it))
        }
    }
    @Test fun genericViewDoesNotSilentlySubscribe() {
        assertEquals(LinkCaptureMode.CHOOSE, externalLinkCaptureMode("android.intent.action.VIEW", "text/html"))
        assertEquals(LinkCaptureMode.CHOOSE, externalLinkCaptureMode("android.intent.action.VIEW", null))
        assertNull(externalLinkCaptureMode("android.intent.action.MAIN", null))
    }
    @Test fun sharedTitleAndUrlAreSeparated() {
        val input = parseSharedLinks("The case for quiet reading\nhttps://example.org/story")
        assertEquals(listOf("https://example.org/story"), input.urls)
        assertEquals("The case for quiet reading", input.titleHint)
        assertFalse(input.requiresSelection)
    }
    @Test fun explicitSubjectWinsOverSharedProse() {
        assertEquals("Real title", parseSharedLinks("read https://example.org/a", "Real title").titleHint)
    }
    @Test fun multipleUrlsRequireExplicitSelectionAndDoNotUseArbitraryProseAsTitle() {
        val input = parseSharedLinks("Two choices: https://example.org/a and https://example.org/b")
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), input.urls)
        assertTrue(input.requiresSelection)
        assertNull(input.titleHint)
    }
    @Test fun duplicateSharedUrlsDoNotCreateAmbiguousCandidates() {
        val input = parseSharedLinks("https://example.org/a\nhttps://example.org/a")
        assertEquals(1, input.urls.size)
        assertFalse(input.requiresSelection)
    }
    @Test fun surroundingPunctuationIsNotPartOfUrl() {
        assertEquals(listOf("https://example.org/a"), parseSharedLinks("Read (https://example.org/a).").urls)
        assertEquals(listOf("https://example.org/a_(b)"), parseSharedLinks("https://example.org/a_(b)").urls)
    }
    @Test fun unsafeSchemesCredentialsAndMissingHostsAreNotCaptureCandidates() {
        assertEquals(emptyList<String>(), parseSharedLinks("javascript:alert(1) file:///a https://user:secret@example.org/a https:///missing").urls)
    }
    @Test fun queryAndFragmentAreNotStrippedFromCapturedArticle() {
        assertEquals(listOf("https://example.org/read?id=7&kind=essay#section"),
            parseSharedLinks("https://example.org/read?id=7&kind=essay#section").urls)
    }
    @Test fun logicalAnchorSurvivesReflowAndDeletionClampsOnlyMissingAnchor() {
        assertEquals(1, savedPageForAnchor(listOf(listOf("a", "b"), listOf("c", "d")), "c", 8))
        assertEquals(2, savedPageForAnchor(listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d")), "c", 1))
        assertEquals(0, savedPageForAnchor(listOf(listOf("a")), "removed", 8))
        assertEquals(0, savedPageForAnchor(emptyList(), "c", 8))
    }
}
