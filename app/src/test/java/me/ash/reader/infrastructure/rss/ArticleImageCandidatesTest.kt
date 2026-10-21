package me.ash.reader.infrastructure.rss

import org.junit.Assert.*
import org.junit.Test

class ArticleImageCandidatesTest {
    private val url = "https://news.example.com/story/123"

    @Test fun publicBaseIsResolvedButPrivateBaseCannotRetargetCandidates() {
        assertEquals(listOf("https://cdn.example.com/photos/hero.jpg"), rankedArticleImageCandidates(
            "<base href='https://cdn.example.com/photos/'><meta property='og:image' content='hero.jpg'>", url))
        assertEquals(listOf("https://news.example.com/story/hero.jpg"), rankedArticleImageCandidates(
            "<base href='http://127.0.0.1/'><img src='hero.jpg'>", url))
    }

    @Test fun fourthSavedCandidateRemainsAvailableAfterThreeFailures() {
        val all = (1..6).map { "https://example.com/$it.jpg" }
        assertEquals(all.drop(3), articleImageAlternates(emptyList<String>() + all, all.take(3).toSet()))
    }

    @Test fun metadataOutranksBodyAndPreservesStoredPhotograph() {
        val html = """<meta property='og:image' content='/og.jpg'>
            <meta name='twitter:image' content='/twitter.jpg'><article><img src='/body.jpg'></article>"""
        assertEquals(listOf("https://news.example.com/saved.jpg", "https://news.example.com/og.jpg",
            "https://news.example.com/twitter.jpg", "https://news.example.com/body.jpg"),
            rankedArticleImageCandidates(html, url, "/saved.jpg"))
    }

    @Test fun articleJsonLdSupportsGraphsImageObjectsArraysAndRelativeUrls() {
        val html = """<script type='application/ld+json'>{"@graph":[
            {"@type":"Organization","logo":"/logo.jpg"},
            {"@type":["Thing","NewsArticle"],"image":[{"contentUrl":"../hero.jpg"},"//cdn.example.com/second.jpg"],"thumbnailUrl":"/third.jpg"}]}</script>"""
        assertEquals(listOf("https://news.example.com/hero.jpg", "https://cdn.example.com/second.jpg", "https://news.example.com/third.jpg"),
            rankedArticleImageCandidates(html, url))
    }

    @Test fun malformedJsonDoesNotHideOtherSources() {
        assertEquals(listOf("https://news.example.com/good.jpg"), rankedArticleImageCandidates(
            "<script type='application/ld+json'>{not json</script><img src='/good.jpg'>", url))
    }

    @Test fun selectsEditorialPhotosInsteadOfHeaderAvatarsPixelsAndLogos() {
        val html = """<header><img src='/header.jpg'></header><article>
            <img src='/logo.png'><img src='/avatar.jpg'><img src='/beacon.jpg' width='1'>
            <img src='/hidden.jpg' aria-hidden='true'><img src='/real.jpg'></article>"""
        assertEquals(listOf("https://news.example.com/real.jpg"), rankedArticleImageCandidates(html, url))
    }

    @Test fun lazyPictureAndResponsiveSourcesProvideRankedAlternates() {
        val html = """<picture><source srcset='/large.webp 1200w'><img src='/fallback.jpg'
            data-src='/lazy.jpg' srcset='/small.jpg 400w, /large.jpg 1200w'></picture>"""
        assertEquals(listOf("https://news.example.com/lazy.jpg", "https://news.example.com/large.webp",
            "https://news.example.com/large.jpg", "https://news.example.com/small.jpg", "https://news.example.com/fallback.jpg"),
            rankedArticleImageCandidates(html, url))
    }

    @Test fun preservesSignedUrlsAndDeduplicatesAcrossMetadataAndBody() {
        val signed = "https://cdn.example.com/render?token=X%2FY+Z%3D&crop=1,2"
        val escaped = signed.replace("&", "&amp;")
        assertEquals(listOf(signed), rankedArticleImageCandidates(
            "<meta property='og:image' content='$escaped'><img data-src='$escaped' src='$escaped'>", url, signed))
    }

    @Test fun refusesUnsafeMetadataAndKeepsUsefulLaterCandidate() {
        val bad = listOf("data:image/png;base64,AAA", "file:///image.jpg", "http://127.0.0.1/a.jpg",
            "http://router.local/a.jpg", "https://user:pass@example.com/a.jpg", "/episode.mp3")
        val html = bad.joinToString("") { "<meta property='og:image' content='$it'>" } + "<img src='/good.jpg'>"
        assertEquals(listOf("https://news.example.com/good.jpg"), rankedArticleImageCandidates(html, url))
    }

    @Test fun candidateAndSavedHtmlInspectionAreBounded() {
        assertEquals(6, rankedArticleImageCandidates((0..30).joinToString("") { "<img src='/$it.jpg'>" }, url).size)
        assertTrue(rankedArticleImageCandidates("x".repeat(ARTICLE_IMAGE_MAX_HTML_BYTES.toInt()) + "<img src='/too-late.jpg'>", url).isEmpty())
    }

    @Test fun briefCanKeepFeedImagesWithoutProbingArticlePage() {
        assertNull(articleImageRequest(null, null, url, allowMetadataLookup = false))
        val request = articleImageRequest("/saved.jpg", null, url, allowMetadataLookup = false) as ArticleImageRequest
        assertNull(request.articleUrl)
        assertEquals(listOf("https://news.example.com/saved.jpg"), request.candidates)
    }

    @Test fun existingIntranetFeedImageUsesLegacyPathButNeverMetadataEnrichment() {
        assertEquals("http://192.168.1.5/photo.jpg", articleImageRequest("http://192.168.1.5/photo.jpg", null, url))
        assertEquals("https://intranet.local/photo.jpg", articleImageRequest(null, "<img src='/photo.jpg'>", "https://intranet.local/story"))
        assertNull(articleImageRequest(null, null, "https://intranet.local/story"))
        assertNull(articleImageRequest("file:///secret.png", null, "about:blank"))
    }

    @Test fun directRequestsCannotEscapeAlternateAttemptBudget() {
        val urls = (0..20).map { "https://example.com/$it.jpg" }
        assertEquals(urls.take(6), articleImageAlternates(urls + urls))
        assertEquals(urls.drop(4).take(2), articleImageAlternates(urls, urls.take(4).toSet()))
        assertTrue(articleImageAlternates(urls, urls.take(6).toSet()).isEmpty())
        assertEquals(listOf(urls[0]), articleImageAlternates(listOf("file:///secret", "http://localhost/photo", urls[0], urls[0])))
    }
}
