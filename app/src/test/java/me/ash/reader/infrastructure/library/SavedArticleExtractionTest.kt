package me.ash.reader.infrastructure.library

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic article fixtures; never contacts a publisher, provider, or user URL. */
class SavedArticleExtractionTest {
    private val paragraph = "A long synthetic editorial paragraph describes the history of a public garden, its trees, paths, and visitors. " +
        "The local gardeners record seasonal changes and explain the planting choices to their neighbors."
    private fun body() = "<article><h1>Fixture headline</h1>" + (1..5).joinToString("") { "<p>$paragraph</p>" } + "<img src='/media/garden.jpg' alt='Garden'></article>"

    @Test fun extractsNormalArticleMetadataAndUsesFinalUrlForRelativeImages() {
        val html = "<html><head><title>Fixture document title</title><meta property='og:title' content='Fixture headline'>" +
            "<meta name='author' content='Fixture author'><meta property='og:site_name' content='Fixture Journal'>" +
            "<meta name='description' content='A synthetic description.'></head><body>${body()}</body></html>"
        val article = extractSavedArticle(html, "https://publisher.example.com/redirected/story", "Shared title")
        assertEquals("Fixture headline", article.title)
        assertEquals("Fixture author", article.author)
        assertEquals("Fixture Journal", article.sourceName)
        assertEquals("A synthetic description.", article.excerpt)
        assertTrue(article.html.contains("seasonal changes"))
        assertEquals(listOf("https://publisher.example.com/media/garden.jpg"), SavedDocumentImages.sources(article.html, "https://original.example.com/story"))
    }
    @Test fun missingMetadataUsesSharedTitleHostAndBoundedTextExcerpt() {
        val article = extractSavedArticle("<html><body>${body()}</body></html>", "https://publisher.example.com/story", "Shared title")
        assertEquals("Shared title", article.title)
        assertNull(article.author)
        assertEquals("publisher.example.com", article.sourceName)
        assertTrue(requireNotNull(article.excerpt).length <= 280)
    }
    @Test fun emptyDocumentCannotBeMarkedReady() {
        try { extractSavedArticle("<html><body></body></html>", "https://example.com/story", null); fail("No article text") }
        catch (_: IOException) { }
    }
}
