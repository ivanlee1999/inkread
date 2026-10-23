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
        assertEquals(listOf("https://example.com/large.jpg"), SavedDocumentImages.sources(saved, base))
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
        assertEquals(listOf("https://example.com/photo.jpg", "https://example.com/big.jpg"), SavedDocumentImages.sources(html, base))
    }
    @Test fun selectsUsefulWidthInsteadOfTinyFirstEntryOrHugeOriginal() {
        val html = "<img src='/fallback.jpg' srcset='/tiny.jpg 160w, /huge.jpg 8000w, /medium.jpg 800w, /reading.jpg 1600w'>"
        assertEquals(listOf("https://example.com/reading.jpg", "https://example.com/medium.jpg",
            "https://example.com/tiny.jpg", "https://example.com/fallback.jpg"),
            SavedDocumentImages.candidateGroups(html, base).values.single())
        assertEquals(listOf("https://example.com/large.jpg"), SavedDocumentImages.sources(
            "<img srcset='/huge.jpg 8000w, /large.jpg 2000w'>", base))
    }

    @Test fun densitySelectionAndFallbackRemainBoundedAcrossOfflineRewrites() {
        val html = "<figure><img src='/fallback.jpg' srcset='/tiny.jpg 0.5x, /standard.jpg 1x, /reading.jpg 2x, /huge.jpg 4x' alt='Original alt'><figcaption>Photo caption</figcaption></figure>"
        val candidates = SavedDocumentImages.candidateGroups(html, base)
        assertEquals(listOf("https://example.com/reading.jpg", "https://example.com/standard.jpg",
            "https://example.com/tiny.jpg", "https://example.com/fallback.jpg"), candidates.values.single())
        val saved = SavedDocumentImages.rewrite(html, base, emptyMap())
        val path = File(System.getProperty("java.io.tmpdir"), "saved-responsive-fixture.img").absolutePath
        val rewritten = SavedDocumentImages.rewrite(saved, base, mapOf(candidates.keys.single() to path))
        assertEquals(candidates, SavedDocumentImages.candidateGroups(rewritten, base))
        assertEquals(mapOf(candidates.keys.single() to path), SavedDocumentImages.retained(rewritten, base) { it == path })
        val document = Jsoup.parseBodyFragment(rewritten)
        assertEquals("Photo caption", document.selectFirst("figcaption")!!.text())
        assertEquals("Original alt", document.selectFirst("img")!!.attr("alt"))
        assertEquals(File(path).toURI().toString(), document.selectFirst("img")!!.attr("src"))
        assertFalse(document.selectFirst("img")!!.hasAttr("srcset"))
    }

    @Test fun signedProxyUrlsWithCommasSurviveSelectionAndOfflineRoundTrip() {
        val proxy = "https://reader.example.net/proxy?url=https%3A%2F%2Fpublisher.example%2Fphoto.jpg&token=X%2FY+Z%3D&crop=1,2"
        val html = "<img src='${proxy.replace("&", "&amp;")}' srcset='${proxy.replace("&", "&amp;")} 1600w, /small.jpg 400w'>"
        assertEquals(proxy, SavedDocumentImages.sources(html, base).single())
        val candidates = SavedDocumentImages.candidateGroups(html, base)
        assertEquals(listOf(proxy, "https://example.com/small.jpg"), candidates.values.single())
        assertEquals(candidates, SavedDocumentImages.candidateGroups(SavedDocumentImages.rewrite(html, base, emptyMap()), base))
    }

    @Test fun legacySavedPrimaryAndRetainedFileNeverChangeWhenAlternativesExist() {
        val path = File(System.getProperty("java.io.tmpdir"), "legacy-saved-fixture.img").absolutePath
        val html = "<img data-inkread-saved-source='https://example.com/original.jpg' src='${File(path).toURI()}' srcset='/new.jpg 1600w'>"
        val rewritten = SavedDocumentImages.rewrite(html, base, mapOf("https://example.com/original.jpg" to path))
        assertEquals(listOf("https://example.com/original.jpg"), SavedDocumentImages.sources(rewritten, base))
        assertEquals(mapOf("https://example.com/original.jpg" to path), SavedDocumentImages.retained(rewritten, base) { true })
    }

    @Test fun sourceOnlyPicturesKeepAllBoundedAlternatesWithoutOnlineSources() {
        val html = "<figure><picture><source data-srcset='/small.webp 400w, /large.webp 1600w'><source srcset='/fallback.jpg 1600w'></picture><figcaption>Kept caption</figcaption></figure>"
        val saved = SavedDocumentImages.rewrite(html, base, emptyMap())
        assertEquals(listOf("https://example.com/large.webp", "https://example.com/small.webp", "https://example.com/fallback.jpg"),
            SavedDocumentImages.candidateGroups(saved, base).values.single())
        val document = Jsoup.parseBodyFragment(saved)
        assertEquals(1, document.select("img").size)
        assertEquals("Kept caption", document.selectFirst("figcaption")!!.text())
        assertTrue(document.select("source[src], source[srcset], source[data-srcset], img[src], img[srcset]").isEmpty())
    }

    @Test fun candidateInspectionAndRetryMetadataAreBounded() {
        val srcset = (1..100).joinToString(", ") { "/$it.jpg ${it * 20}w" }
        val html = "<img src='/fallback.jpg' srcset='$srcset'>"
        val candidates = SavedDocumentImages.candidateGroups(html, base).values.single()
        assertEquals(SavedDocumentImages.MAX_CANDIDATES, candidates.size)
        assertEquals("https://example.com/64.jpg", candidates.first())
        assertEquals("https://example.com/fallback.jpg", candidates.last())
        val saved = SavedDocumentImages.rewrite(html, base, emptyMap())
        assertEquals(candidates, SavedDocumentImages.candidateGroups(saved, base).values.single())
        val oversizedMetadata = "<img data-inkread-saved-source='/primary.jpg' " +
            (1..20).joinToString(" ") { "data-inkread-saved-alternate-$it='/$it.jpg'" } + ">"
        assertEquals(4, SavedDocumentImages.candidateGroups(oversizedMetadata, base).values.single().size)
    }

    @Test fun invalidDescriptorsAreIgnoredAndDataPlaceholdersDoNotWin() {
        val html = "<img src='data:image/gif;base64,AA==' data-src='/lazy.jpg' " +
            "srcset='/bad.jpg -1w, /bad2.jpg NaNx, /bad3.jpg 2x 3x, /good.jpg 1600w'>"
        assertEquals(listOf("https://example.com/good.jpg", "https://example.com/lazy.jpg"),
            SavedDocumentImages.candidateGroups(html, base).values.single())
    }

    @Test fun srcsetOnlyEmbeddedImagesStillCountAsMissing() {
        val html = "<img srcset='data:image/png;base64,AA== 1x'>"
        val saved = SavedDocumentImages.rewrite(html, base, emptyMap())
        assertEquals(listOf("data:image/png;base64,AA=="), SavedDocumentImages.sources(saved, base))
        assertFalse(Jsoup.parseBodyFragment(saved).selectFirst("img")!!.hasAttr("src"))
    }

    @Test fun lengthBoundDoesNotCreateTruncatedSignedUrl() {
        val huge = "https://proxy.example.com/image?signature=" + "x".repeat(40 * 1024)
        val html = "<img src='/fallback.jpg' srcset='/valid.jpg 800w, $huge 1600w'>"
        assertEquals(listOf("https://example.com/valid.jpg", "https://example.com/fallback.jpg"),
            SavedDocumentImages.candidateGroups(html, base).values.single())
    }

    @Test fun manyResponsiveSizesCannotCrowdOutLazyProxyAndOrdinaryFallback() {
        val proxy = "https://reader.example.net/image?url=https%3A%2F%2Fpublisher.example%2Fphoto.jpg&signature=ABC%2F123"
        val html = "<img src='/fallback.jpg' data-src='${proxy.replace("&", "&amp;")}' " +
            "srcset='/small.jpg 400w, /medium.jpg 800w, /large.jpg 1600w, /huge.jpg 4000w'>"
        assertEquals(listOf("https://example.com/large.jpg", "https://example.com/medium.jpg", proxy,
            "https://example.com/fallback.jpg"), SavedDocumentImages.candidateGroups(html, base).values.single())
    }

    @Test fun reservingSrcDoesNotDisplaceLazyFallbackAlreadyAtCandidateLimit() {
        val html = "<img src='/fallback.jpg' data-src='/lazy.jpg' " +
            "srcset='/small.jpg 400w, /medium.jpg 800w, /large.jpg 1600w'>"
        assertEquals(listOf("https://example.com/large.jpg", "https://example.com/medium.jpg",
            "https://example.com/lazy.jpg", "https://example.com/fallback.jpg"),
            SavedDocumentImages.candidateGroups(html, base).values.single())
    }

}
