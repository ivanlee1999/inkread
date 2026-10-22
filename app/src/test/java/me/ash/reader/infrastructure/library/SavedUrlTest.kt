package me.ash.reader.infrastructure.library

import org.junit.Assert.*
import org.junit.Test

class SavedUrlTest {
    @Test fun conservativeDedupRemovesFragmentAndDefaultPortOnly() {
        assertEquals("https://example.com/Article?b=2&a=1&utm_source=feed", normalizedSavedUrl(" HTTPS://EXAMPLE.COM:443/Article?b=2&a=1&utm_source=feed#section "))
        assertEquals("http://example.com/", normalizedSavedUrl("http://EXAMPLE.COM:80#top"))
    }
    @Test fun distinctQueriesPathsSchemesAndEscapesRemainDistinct() {
        val urls = listOf("https://example.com/A", "https://example.com/a", "http://example.com/a",
            "https://example.com/a?b=2&a=1", "https://example.com/a?a=1&b=2", "https://example.com/a?utm_source=rss",
            "https://example.com/a/", "https://example.com/%61", "https://example.com/a?")
        assertEquals(urls.size, urls.map(::normalizedSavedUrl).toSet().size)
    }
    @Test fun invalidAndCredentialUrlsRejectedWithoutNetwork() {
        listOf("file:///x", "example.com", "mailto:a@example.com", "https://user:pass@example.com/a", "https://", "https://example.com/a\nb").forEach {
            try { normalizedSavedUrl(it); fail(it) } catch (_: IllegalArgumentException) { }
        }
    }
}
