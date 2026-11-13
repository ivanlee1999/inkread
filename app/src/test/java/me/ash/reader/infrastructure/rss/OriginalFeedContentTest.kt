package me.ash.reader.infrastructure.rss

import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class OriginalFeedContentTest {
    private fun entry(url: String, html: String) = SyndEntryImpl().apply {
        link = url
        contents = listOf(SyndContentImpl().apply { type = "text/html"; value = html })
    }
    private val target = "https://example.com/story?edition=1"

    @Test fun exactUniqueLinkSelectsOriginalHtmlWithoutPublisherExtraction() {
        val body = "<p>Original RSS 摘要。</p><a href='../source'>Source</a>"
        val feed = SyndFeedImpl().apply { entries = listOf(entry("https://example.com/other", "Wrong"), entry(target, body)) }
        assertEquals(body, originalFeedBody(feed, target))
    }

    @Test fun missingAmbiguousAndDifferentQueryLinksNeverSubstituteAnotherStory() {
        listOf(emptyList(), listOf(entry("https://example.com/story?edition=2", "Wrong")),
            listOf(entry(target, "First"), entry(target, "Second"))).forEach { entries ->
            try {
                originalFeedBody(SyndFeedImpl().apply { this.entries = entries }, target)
                fail("Expected original source to be unavailable")
            } catch (_: IOException) { }
        }
    }

    @Test fun descriptionFallbackAndOriginalBodyBoundsApply() {
        val feed = SyndFeedImpl().apply { entries = listOf(SyndEntryImpl().apply {
            link = target; description = SyndContentImpl().apply { value = "<p>Short RSS.</p>" }
        }) }
        assertEquals("<p>Short RSS.</p>", originalFeedBody(feed, target))
        feed.entries = listOf(entry(target, "中".repeat(MAX_FEED_BODY_BYTES.toInt() / 3 + 1)))
        try { originalFeedBody(feed, target); fail("Expected byte bound") } catch (_: IOException) { }
    }

    @Test fun automaticOriginalFetchRejectsPrivateAndTokenizedFeedUrls() {
        listOf("https://example.com/rss", "https://example.com/?feed=rss2").forEach {
            assertFalse(it, isPrivateOriginalFeedUrl(it))
        }
        listOf("https://example.com/rss?token=synthetic", "https://example.com/private/feed.xml",
            "https://example.com/%2570rivate/rss", "https://example.com/rss#private",
            "https://example.com/rss?feed=secret", "https://127.0.0.1/rss", "https://example.local/rss",
            "https://name:password@example.com/rss").forEach { assertTrue(it, isPrivateOriginalFeedUrl(it)) }
    }
}
