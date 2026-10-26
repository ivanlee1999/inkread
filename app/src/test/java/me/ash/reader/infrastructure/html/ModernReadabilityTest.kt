package me.ash.reader.infrastructure.html

import java.util.concurrent.CancellationException
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ModernReadabilityTest {
    private val engine = ModernReadability()
    private val paragraph = "An article paragraph has enough useful context to be recognized as prose, with punctuation, detail, and a complete ending. ".repeat(5)
    private fun page(body: String, head: String = "") = "<!doctype html><html lang='en'><head><title>Readable article title</title>$head</head><body><article><h1>Readable article title</h1>$body</article></body></html>"
    private fun extract(html: String) = checkNotNull(engine.extract(html, "https://example.org/news/story"))

    @Test fun retainsBodyOrderImagesAndTitle() {
        val result = extract(page("<p>FIRST $paragraph</p><figure><img src='/photo.jpg'><figcaption>Photo caption</figcaption></figure><p>LAST $paragraph</p>"))
        assertEquals("Readable article title", result.title)
        assertTrue(result.textContent.indexOf("FIRST") < result.textContent.indexOf("LAST"))
        val document = Jsoup.parseBodyFragment(result.content)
        assertEquals("https://example.org/photo.jpg", document.selectFirst("img")!!.attr("src"))
        assertTrue(document.text().contains("Photo caption"))
    }

    @Test fun preservesFinalUrlAndBaseIncludingLazyImagesAndSrcset() {
        val result = extract(page("<p>$paragraph <a href='../other?q=1&amp;x=2#end'>a linked detail</a></p><img data-src='image.png' data-srcset='small.png 1x, large.png 2x'><p>$paragraph</p>", "<base href='/assets/'>"))
        assertEquals("https://example.org/assets/", result.baseUri)
        val document = Jsoup.parseBodyFragment(result.content)
        assertEquals("https://example.org/other?q=1&x=2#end", document.selectFirst("a")!!.attr("href"))
        assertEquals("https://example.org/assets/image.png", document.selectFirst("img")!!.attr("src"))
        assertEquals("https://example.org/assets/small.png 1x, https://example.org/assets/large.png 2x", document.selectFirst("img")!!.attr("srcset"))
    }

    @Test fun knownLazyAttributesRetainExtensionlessImagesCaptionsAndBodyOrder() {
        val signed = "https://cdn.example.org/image?token=X%2FY+Z%3D&crop=1,2&format=avif"
        for (attribute in listOf("data-src", "data-original", "data-lazy-src")) {
            val result = extract(page("<p>FIRST $paragraph</p><figure>" +
                "<img $attribute='${signed.replace("&", "&amp;")}' alt='Garden' data-credit='Photographer'>" +
                "<figcaption>Garden caption</figcaption></figure><p>LAST $paragraph</p>"))
            val document = Jsoup.parseBodyFragment(result.content)
            val image = requireNotNull(document.selectFirst("img")) { attribute }
            assertEquals(attribute, signed, image.attr("src"))
            assertEquals(signed, image.attr(attribute))
            assertEquals("Photographer", image.attr("data-credit"))
            assertEquals("Garden", image.attr("alt"))
            assertTrue(result.textContent.indexOf("FIRST") < result.textContent.indexOf("Garden caption"))
            assertTrue(result.textContent.indexOf("Garden caption") < result.textContent.indexOf("LAST"))
        }
    }

    @Test fun relativeLazyImageAndResponsiveAlternativeUseDocumentBase() {
        val result = extract(page("<p>$paragraph</p><figure>" +
            "<img data-original='../image?id=42&amp;format=avif' data-srcset='small?id=1 1x, large?id=2 2x'>" +
            "<figcaption>Responsive caption</figcaption></figure><p>$paragraph</p>", "<base href='/assets/photos/'>"))
        val image = Jsoup.parseBodyFragment(result.content).selectFirst("img")!!
        assertEquals("https://example.org/assets/image?id=42&format=avif", image.attr("src"))
        assertEquals("https://example.org/assets/photos/small?id=1 1x, https://example.org/assets/photos/large?id=2 2x", image.attr("srcset"))
        assertEquals("https://example.org/assets/photos/", result.baseUri)
    }

    @Test fun lazyNormalizationPreservesExistingImageAndUnknownAttributes() {
        val result = extract(page("<p>$paragraph</p><figure>" +
            "<img src='/original-image?id=1' data-src='/lazy-image?id=2' data-original='/other-image?id=3' data-credit='Credit'>" +
            "<figcaption>Original photograph</figcaption></figure><p>$paragraph</p>"))
        val image = Jsoup.parseBodyFragment(result.content).selectFirst("img")!!
        assertEquals("https://example.org/original-image?id=1", image.attr("src"))
        assertEquals("/lazy-image?id=2", image.attr("data-src"))
        assertEquals("/other-image?id=3", image.attr("data-original"))
        assertEquals("Credit", image.attr("data-credit"))
    }

    @Test fun lazyNormalizationReplacesOnlyMissingSourcesOrTinyRasterPlaceholders() {
        val tinyGif = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        for (source in listOf("", "null", tinyGif)) {
            val result = extract(page("<p>$paragraph</p><figure><img src='$source' data-lazy-src='/image?id=2'>" +
                "<figcaption>Photo caption</figcaption></figure><p>$paragraph</p>"))
            assertEquals("https://example.org/image?id=2", Jsoup.parseBodyFragment(result.content).selectFirst("img")!!.attr("src"))
        }
        val svg = "data:image/svg+xml;base64,PHN2Zy8+"
        val result = extract(page("<p>$paragraph</p><figure><img src='$svg' data-src='/image?id=2'>" +
            "<figcaption>Vector caption</figcaption></figure><p>$paragraph</p>"))
        assertEquals(svg, Jsoup.parseBodyFragment(result.content).selectFirst("img")!!.attr("src"))
    }

    @Test fun lazyNormalizationSkipsUnsupportedSourcesAndKeepsLaterHttpAlternative() {
        val result = extract(page("<p>$paragraph</p><figure>" +
            "<img data-src='javascript:throw new Error(&quot;EXECUTED&quot;)' data-original='file:///secret' data-lazy-src='//cdn.example.org/image?id=2'>" +
            "<figcaption>Safe photograph</figcaption></figure><p>$paragraph</p>"))
        assertEquals("https://cdn.example.org/image?id=2", Jsoup.parseBodyFragment(result.content).selectFirst("img")!!.attr("src"))
        assertTrue(result.textContent.contains("Safe photograph"))
    }

    @Test fun preservesLocalFragmentsWithoutBaseAndResolvesThemWithBase() {
        val body = "<p>$paragraph<a href='#end'>the ending</a></p><p id='end'>$paragraph</p>"
        assertEquals("#end", Jsoup.parseBodyFragment(extract(page(body)).content).selectFirst("a")!!.attr("href"))
        assertEquals("https://example.org/assets/#end", Jsoup.parseBodyFragment(extract(page(body, "<base href='/assets/'>")).content).selectFirst("a")!!.attr("href"))
    }

    @Test fun publisherScriptsAndAttributesAreNeverExecuted() {
        val poison = "<script>throw new Error('PAGE_EXECUTED'); while(true){}</script><script src='https://example.invalid/never.js'></script>"
        val result = extract(page("$poison<p onclick=\"throw new Error('EVENT_EXECUTED')\">$paragraph</p><p>$paragraph</p>"))
        assertTrue(result.textContent.contains("An article paragraph"))
        assertFalse(result.content.contains("PAGE_EXECUTED"))
        assertFalse(result.content.contains("never.js"))
        // The engine is not the existing renderer sanitizer; caller must still sanitize event attributes.
    }

    @Test fun actualEngineContextRejectsJavaObjectJsonConversion() {
        // Inspect the real private factory without adding any production test hook or JS bridge.
        val nested = ModernReadability::class.java.declaredClasses
        val budgetClass = nested.single { it.simpleName == "Budget" }
        val budgetConstructor = budgetClass.declaredConstructors.single().apply { isAccessible = true }
        val budget = budgetConstructor.newInstance(ModernReadability.Limits(), {})
        val factoryClass = nested.single { it.simpleName == "BoundedContextFactory" }
        val constructor = factoryClass.getDeclaredConstructor(budgetClass).apply { isAccessible = true }
        val factory = constructor.newInstance(budget) as org.mozilla.javascript.ContextFactory
        factory.call { context ->
            try {
                context.javaToJSONConverter.apply(Any())
                fail("Java object conversion must fail closed")
            } catch (expected: SecurityException) {
                assertEquals("Java object conversion is disabled", expected.message)
            }
            val scope = context.initSafeStandardObjects()
            assertEquals(true, context.evaluateString(scope,
                "typeof Packages === 'undefined' && typeof java === 'undefined'", "isolation-test", 1, null))
            Unit
        }
    }

    @Test fun untrustedMarkupCannotBecomeEvaluatedJavaScript() {
        val payload = "\"; throw new Error('STRING_BREAKOUT'); //"
        val result = extract(page("<p>$paragraph $payload</p><p>$paragraph</p>"))
        assertTrue(result.textContent.contains("STRING_BREAKOUT"))
    }

    @Test fun jsonLdIsEscapedDataAndMetadataSurvives() {
        val result = extract(page("<p>$paragraph</p><p>$paragraph</p>", """<script type='application/ld+json'>{"@context":"https://schema.org","@type":"NewsArticle","headline":"Readable article title","author":{"@type":"Person","name":"A < B & C"},"datePublished":"2026-10-23T10:00:00Z"}</script>"""))
        assertEquals("A < B & C", result.byline)
        assertEquals("2026-10-23T10:00:00Z", result.publishedTime)
    }

    @Test fun retainsChineseEmojiEntitiesAndMalformedHtml() {
        val text = "中文文章段落，包含细节、解释和标点符号，应该完整保留。".repeat(20)
        val result = extract("<html lang=zh><title>中文文章标题</title><body><article><p>第一段 $text &amp; &#x1F600;<p>最后一段 $text</article>")
        assertEquals("zh", result.lang)
        assertTrue(result.textContent.contains("第一段"))
        assertTrue(result.textContent.contains("最后一段"))
        assertTrue(result.textContent.contains("& 😀"))
    }

    @Test fun inputByteLimitIncludesMultibyteCharacters() {
        assertFailure(ModernReadability.Failure.INPUT_LIMIT) {
            ModernReadability(ModernReadability.Limits(maxInputBytes = 30)).extract("中".repeat(11), "https://example.org")
        }
    }

    @Test fun maxBodyRejectedBeforeParsing() {
        assertFailure(ModernReadability.Failure.INPUT_LIMIT) {
            ModernReadability(ModernReadability.Limits(maxInputBytes = 100)).extract("x".repeat(101), "https://example.org")
        }
    }

    @Test fun nodeExplosionRejected() {
        assertFailure(ModernReadability.Failure.NODE_LIMIT) {
            ModernReadability(ModernReadability.Limits(maxNodes = 30)).extract(page("<span>x</span>".repeat(100)), "https://example.org")
        }
    }

    @Test fun excessiveDepthRejectedWithoutStackOverflow() {
        assertFailure(ModernReadability.Failure.DEPTH_LIMIT) {
            ModernReadability(ModernReadability.Limits(maxDepth = 20)).extract(page("<div>".repeat(40) + paragraph + "</div>".repeat(40)), "https://example.org")
        }
    }

    @Test fun instructionExhaustionEscapesScriptCatchClauses() {
        assertFailure(ModernReadability.Failure.INSTRUCTION_LIMIT) {
            ModernReadability(ModernReadability.Limits(maxInstructions = 1)).extract(page("<p>$paragraph</p>"), "https://example.org")
        }
    }

    @Test fun deadlineExceededIsBoundedFailure() {
        assertFailure(ModernReadability.Failure.TIME_LIMIT) {
            var delayed = false
            ModernReadability(ModernReadability.Limits(maxMillis = 5)).extract(page("<p>$paragraph</p>"), "https://example.org") {
                if (!delayed) { delayed = true; Thread.sleep(10) }
            }
        }
    }

    @Test fun cancellationPropagatesBeforeAndDuringParsing() {
        val cancelled = CancellationException("cancelled test")
        try {
            engine.extract(page("<p>$paragraph</p>"), "https://example.org") { throw cancelled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        var checks = 0
        try {
            engine.extract(page("<p>$paragraph</p>"), "https://example.org") { if (++checks > 20) throw cancelled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun cancellationDuringJavaScriptIsNotSwallowed() {
        val cancelled = CancellationException("cancelled in interpreter")
        try {
            engine.extract(page("<p>$paragraph</p>"), "https://example.org") {
                if (Thread.currentThread().stackTrace.any { it.methodName == "observeInstructionCount" }) throw cancelled
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun shortChineseArticleRetainsRelativeImage() {
        val result = extract("<html lang='zh'><head><title>中文短文章</title></head><body><article><h1>中文短文章</h1><p>第一段：这是一篇中文短文章，包含说明和标点符号。图片应该正确保留。</p><figure><img src='../images/photo.jpg'><figcaption>图片说明</figcaption></figure><p>最后一段：短文章不应该因为字数少而丢失，原有段落顺序需要保留。</p></article></body></html>")
        assertEquals("中文短文章", result.title)
        assertTrue(result.textContent.contains("最后一段"))
        assertEquals("https://example.org/images/photo.jpg", Jsoup.parseBodyFragment(result.content).selectFirst("img")?.attr("src"))
    }

    @Test fun interruptedThreadIsNotClearedOrConvertedToFailure() {
        Thread.currentThread().interrupt()
        try {
            engine.extract(page("<p>$paragraph</p>"), "https://example.org")
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertTrue(Thread.currentThread().isInterrupted) }
        finally { Thread.interrupted() }
    }

    @Test fun eachExtractionHasFreshGlobalState() {
        val first = extract(page("<p>FIRST $paragraph</p>"))
        val second = extract(page("<p>SECOND $paragraph</p>"))
        assertFalse(second.textContent.contains("FIRST"))
        assertFalse(first.textContent.contains("SECOND"))
    }

    private fun assertFailure(reason: ModernReadability.Failure, block: () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (actual: ModernReadability.ExtractionException) { assertEquals(reason, actual.reason) }
    }
}
