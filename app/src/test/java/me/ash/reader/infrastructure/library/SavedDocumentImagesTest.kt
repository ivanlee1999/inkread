package me.ash.reader.infrastructure.library

import java.io.File
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SavedDocumentImagesTest {
    private val base = "https://example.com/story"
    @Test fun rewriteKeepsTextAndRetrySourcesWithoutNetworkFallback() {
        val html = "<p>Keep this paragraph.</p><picture><source srcset='/large.jpg 2x'><img src='/small.jpg' srcset='/large.jpg 2x' alt='Caption'></picture>"
        val saved = SavedDocumentImages.rewrite(html, base, emptyMap())
        val document = Jsoup.parseBodyFragment(saved)
        assertEquals("Keep this paragraph.", document.selectFirst("p")!!.text())
        assertEquals(listOf("https://example.com/small.jpg"), SavedDocumentImages.sources(saved, base))
        assertFalse(document.selectFirst("img")!!.hasAttr("src"))
        assertFalse(document.selectFirst("img")!!.hasAttr("srcset"))
        assertFalse(document.selectFirst("source")!!.hasAttr("srcset"))
        assertEquals("Caption", document.selectFirst("img")!!.attr("alt"))
    }
    @Test fun retainedPathsAreStableAndNeverTurnIntoNewNetworkSources() {
        val path = File(System.getProperty("java.io.tmpdir"), "saved-image-fixture.img").absolutePath
        val html = SavedDocumentImages.rewrite("<img src='/photo.jpg'>", base, mapOf("https://example.com/photo.jpg" to path))
        assertEquals(listOf("https://example.com/photo.jpg"), SavedDocumentImages.sources(html, base))
        assertEquals(mapOf("https://example.com/photo.jpg" to path), SavedDocumentImages.retained(html, base) { it == path })
        assertTrue(SavedDocumentImages.retained(html, base) { false }.isEmpty())
    }
    @Test fun pictureSourcesAndSrcsetWinOverEmbeddedPlaceholders() {
        val html = "<picture><source srcset='/picture.jpg 1x'><img src='data:image/gif;base64,AA=='></picture>" +
            "<img src='data:image/gif;base64,AA==' srcset='/srcset.jpg 1x'>" +
            "<picture><source srcset='/source-only.jpg 1x'></picture>"
        val saved = SavedDocumentImages.rewrite(html, base, emptyMap())
        assertEquals(listOf("https://example.com/picture.jpg", "https://example.com/srcset.jpg", "https://example.com/source-only.jpg"),
            SavedDocumentImages.sources(saved, base))
        assertEquals(3, Jsoup.parseBodyFragment(saved).select("img").size)
    }
    @Test fun unsupportedEmbeddedImagesRemainExplicitlyCountedMissing() {
        val saved = SavedDocumentImages.rewrite("<p>Readable text</p><img src='data:image/png;base64,AA=='>", base, emptyMap())
        assertEquals(listOf("data:image/png;base64,AA=="), SavedDocumentImages.sources(saved, base))
        assertFalse(Jsoup.parseBodyFragment(saved).selectFirst("img")!!.hasAttr("src"))
    }
    @Test fun lazyAndDuplicateImagesHaveBoundedUniqueSources() {
        val html = "<img data-src='/photo.jpg' src='data:image/gif;base64,x'><img src='/photo.jpg'><img srcset='/second.jpg 1x, /big.jpg 2x'>"
        assertEquals(listOf("https://example.com/photo.jpg", "https://example.com/second.jpg"), SavedDocumentImages.sources(html, base))
    }
}
