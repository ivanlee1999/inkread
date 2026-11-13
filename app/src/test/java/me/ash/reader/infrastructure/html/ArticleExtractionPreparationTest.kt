package me.ash.reader.infrastructure.html

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ArticleExtractionPreparationTest {
    private val publicUrl = "https://www.theverge.com/tech/synthetic-public-story"

    @Test fun `short English and Chinese articles remain eligible without length heuristics`() {
        listOf("The park opens today.", "今天公园开放，欢迎散步。", "Subscribe is the name of the new application.").forEach { text ->
            val ready = ready("<article><p>$text</p></article>")
            assertEquals(text, Jsoup.parse(ready.html).selectFirst("p")!!.text())
        }
    }

    @Test fun `false JSON LD booleans and strings deny hidden body extraction`() {
        listOf("false", "\"false\"", "\"FALSE\"", "\" false \"").forEach { value ->
            val html = """<script type="application/ld+json">{"@type":"NewsArticle","isAccessibleForFree":$value}</script>
                <article hidden><p>Private synthetic body must not escape.</p></article>"""
            assertEquals(
                ArticleExtractionPreparation.Result.RequiresBrowser(ArticleExtractionPreparation.AccessReason.PUBLISHER_RESTRICTED),
                ArticleExtractionPreparation.prepare(html, publicUrl),
            )
        }
    }

    @Test fun `nested graph and hasPart restrictions remain authoritative`() {
        val cases = listOf(
            """{"@graph":[{"@type":"NewsArticle","isAccessibleForFree":false}]}""",
            """[{"@type":"NewsArticle","isAccessibleForFree":true,"hasPart":{"isAccessibleForFree":"false"}}]""",
        )
        cases.forEach { assertRestricted("<script type='application/ld+json'>$it</script><article>Full body</article>") }
    }

    @Test fun `metadata is checked even when gate and body are hidden`() {
        assertRestricted("""<script type="APPLICATION/LD+JSON; charset=utf-8">{"isAccessibleForFree":false}</script>
            <div class="paywall" hidden>Subscribe to continue reading</div><article hidden>Full synthetic body</article>""")
    }

    @Test fun `microdata restriction and malformed explicit JSON restriction fail closed`() {
        assertRestricted("<meta itemprop='isAccessibleForFree' content='false'><article>Hidden body</article>")
        assertRestricted("""<script type="application/ld+json">{"isAccessibleForFree":false, BROKEN</script>""")
    }

    @Test fun `true metadata and a prose string containing false are not restrictions`() {
        val html = """<script type="application/ld+json">{"isAccessibleForFree":true,"description":"Discussing isAccessibleForFree: false"}</script>
            <article><p>A short public report.</p></article>"""
        assertTrue(ArticleExtractionPreparation.prepare(html, publicUrl) is ArticleExtractionPreparation.Result.Ready)
    }

    @Test fun `ordinary script config is not treated as JSON LD metadata`() {
        ready("""<script>const example = {isAccessibleForFree:false};</script><article>Public body.</article>""")
    }

    @Test fun `throwing API carries reason but never private body`() {
        try {
            ArticleExtractionPreparation.prepareOrThrow(
                """<script type="application/ld+json">{"isAccessibleForFree":false}</script><article>SECRET_BODY</article>""",
                publicUrl,
            )
            fail("Expected browser access to be required")
        } catch (error: ArticleRequiresBrowserException) {
            assertEquals(ArticleExtractionPreparation.AccessReason.PUBLISHER_RESTRICTED, error.reason)
            assertFalse(error.message.orEmpty().contains("SECRET_BODY"))
        }
    }

    @Test fun `visible paywall remains blocked before publisher cleanup`() {
        val html = publicVergeFixture().replace("</article>", "<div class='paywall-overlay'>Membership required.</div></article>")
        assertReason(html, ArticleExtractionPreparation.AccessReason.ACCESS_GATE)
    }

    @Test fun `specific sign in subscription and Chinese access notices require browser`() {
        listOf(
            "Sign in to continue reading.", "Subscribe to read the full article.",
            "This article is only available to subscribers.", "You've reached your free article limit.",
            "登录后查看全文", "訂閱後繼續閱讀",
        ).forEach { notice ->
            assertReason("<article><p>Public introduction.</p><div>$notice</div><div hidden>Private body</div></article>",
                ArticleExtractionPreparation.AccessReason.ACCESS_GATE)
        }
    }

    @Test fun `challenge pages require browser without reading their hidden article`() {
        listOf(
            "<form id='challenge-form'></form>",
            "<h1>Verify you are human</h1>",
            "<p>Enable JavaScript and cookies to continue.</p>",
        ).forEach { challenge ->
            assertReason("$challenge<article hidden>Hidden content</article>", ArticleExtractionPreparation.AccessReason.BROWSER_CHALLENGE)
        }
    }

    @Test fun `inactive explicitly hidden gate is not an active visible restriction`() {
        listOf("hidden", "aria-hidden='true'", "style='display: none !important'", "style='visibility:hidden'").forEach { attr ->
            ready("<div $attr><div class='paywall'>Subscribe to continue reading.</div></div><article><p>Public report.</p></article>")
        }
    }

    @Test fun `ordinary subscribe links newsletter signup and recaptcha disclosure do not block public article`() {
        ready("""<nav><a href="/subscribe">Subscribe</a><a href="/login">Sign in</a></nav>
            <article><p>Readers subscribe to the newsletter for updates.</p>
            <form><label>Email</label><input type="email"><button>Subscribe</button>
            <p>This site is protected by reCAPTCHA.</p></form></article>""")
    }

    @Test fun `public Verge cleanup removes only observed widgets and preserves editorial content`() {
        val document = Jsoup.parse(ready(publicVergeFixture()).html)
        assertEquals(listOf("First synthetic paragraph with a source.", "最后一段，保留中文与标点。"),
            document.select("p.editorial").map { it.text() })
        assertEquals("images/garden.jpg", document.selectFirst("article img")!!.attr("src"))
        assertEquals("A synthetic garden caption.", document.selectFirst("figcaption")!!.text())
        assertEquals("../source", document.selectFirst("p.editorial a")!!.attr("href"))
        assertTrue(document.text().contains("by Example Author"))
        assertFalse(document.text().contains("Posts from this"))
        assertFalse(document.text().contains("Follow topics and authors"))
        assertFalse(document.text().contains("Email signup notice"))
        assertNotNull(document.getElementById("zephr-anchor"))
        assertNotNull(document.getElementById("article-body"))
    }

    @Test fun `Verge follow and newsletter words in editorial markup are retained`() {
        val html = """<article><p>Follow topics and authors is the label being discussed.</p>
            <aside id="popover-source"><p>A useful editorial aside.</p></aside>
            <div class="duet--cta--newsletter"><p>The newsletter is the subject of this story.</p></div></article>"""
        val text = Jsoup.parse(ready(html).html).text()
        assertTrue(text.contains("Follow topics and authors is the label being discussed."))
        assertTrue(text.contains("A useful editorial aside."))
        assertTrue(text.contains("The newsletter is the subject of this story."))
    }

    @Test fun `publisher cleanup cannot apply to other or lookalike hosts`() {
        listOf("https://example.com/story", "https://www.theverge.com.example.org/story", "not a URL").forEach { url ->
            val result = ArticleExtractionPreparation.prepare(publicVergeFixture(), url) as ArticleExtractionPreparation.Result.Ready
            assertTrue(Jsoup.parse(result.html).text().contains("Posts from this topic"))
            assertTrue(Jsoup.parse(result.html).text().contains("Email signup notice"))
        }
    }

    @Test fun `newsletter form cleanup does not remove sibling article paragraphs`() {
        val doc = Jsoup.parse(ready(publicVergeFixture()).html)
        assertEquals(2, doc.select("#article-body p.editorial").size)
        assertTrue(doc.select("form").isEmpty())
    }

    @Test fun `oversized ASCII and UTF8 input fails closed before DOM parsing`() {
        val limit = ArticleExtractionPreparation.MAX_INPUT_BYTES
        assertReason("x".repeat(limit + 1), ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT)
        // Character count is below the input limit but encoded byte count is above it.
        assertReason("中".repeat(limit / 3 + 1), ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT)
    }

    @Test fun `deep DOM is not traversed or extracted beyond the preparation budget`() {
        val depth = ArticleExtractionPreparation.MAX_DOM_DEPTH + 1
        assertReason("<div>".repeat(depth) + "Private synthetic body" + "</div>".repeat(depth),
            ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT)
    }

    @Test fun `excess DOM node count fails closed`() {
        assertReason("<article>" + "<br>".repeat(ArticleExtractionPreparation.MAX_DOM_NODES + 1) + "</article>",
            ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT)
    }

    @Test fun `deep JSON LD cannot hide a restricted property past the depth budget`() {
        val depth = 10_000
        val json = "[".repeat(depth) + "{\"isAccessibleForFree\":false}" + "]".repeat(depth)
        assertReason("<script type='application/ld+json'>$json</script><article hidden>Private body</article>",
            ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT)
    }

    @Test fun `oversized JSON LD fails closed even if restriction is at the end`() {
        val json = "{\"description\":\"" + "x".repeat(ArticleExtractionPreparation.MAX_METADATA_BYTES) +
            "\",\"isAccessibleForFree\":false}"
        assertReason("<script type='application/ld+json'>$json</script><article hidden>Private body</article>",
            ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT)
    }

    @Test fun `wide JSON LD cannot hide restricted property beyond shared token budget`() {
        val array = "[" + List(ArticleExtractionPreparation.MAX_METADATA_TOKENS) { "0" }.joinToString(",") + "]"
        val html = "<script type='application/ld+json'>$array</script>" +
            "<script type='application/ld+json'>{\"isAccessibleForFree\":false}</script>"
        assertReason(html, ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT)
    }

    @Test fun `metadata string braces are not counted as nesting`() {
        ready("<script type='application/ld+json'>{\"description\":\"" + "[{}]".repeat(100) +
            "\",\"isAccessibleForFree\":true}</script><article><p>A public report.</p></article>")
    }

    @Test fun `only visible top level Verge body components are preserved after access checks`() {
        val html = structuredVergeFixture()
        val body = Jsoup.parse(checkNotNull(ready(html).articleHtml))
        assertEquals(listOf("First editorial paragraph.", "Last editorial paragraph."), body.select("p").map { it.text() })
        assertEquals(1, body.select("#nested").size)
        assertEquals("Read the review.", body.selectFirst("h5")!!.text())
        assertEquals("$249 at Shop", body.selectFirst(".duet--article--product-card")!!.text())
        assertEquals("/hero.jpg", body.selectFirst("figure img")!!.attr("src"))
        assertEquals("Lead credit", body.selectFirst("figcaption")!!.text())
        assertFalse(body.text().contains("Related story"))
        assertFalse(body.text().contains("Hidden paragraph"))
        assertTrue(body.select("form,button,script").isEmpty())
        assertNull((ArticleExtractionPreparation.prepare(html, "https://example.com/story") as
            ArticleExtractionPreparation.Result.Ready).articleHtml)
        assertNull((ArticleExtractionPreparation.prepare(html, "https://theverge.com.example.com/story") as
            ArticleExtractionPreparation.Result.Ready).articleHtml)
        assertRestricted("<script type='application/ld+json'>{\"isAccessibleForFree\":false}</script>" + html)
        assertReason(html + "<div class='paywall'>Subscription required.</div>", ArticleExtractionPreparation.AccessReason.ACCESS_GATE)
    }

    @Test fun `unknown Verge markup retains generic parser fallback`() {
        assertNull(ready("<article><p>A public report with no body components.</p></article>").articleHtml)
        assertNull(ready("""<article><div class="duet--article--lede"><img src="/hero.jpg"><cite>Lead credit</cite></div>
            <div class="duet--article--article-body-component"><form>Signup</form></div></article>""").articleHtml)
    }

    private fun structuredVergeFixture() = """
        <nav>Navigation chrome</nav><article>
        <div class="duet--article--lede"><p>Duplicate summary</p><p>Duplicate summary</p>
          <img src="/hero.jpg"><img src="/hero-mobile.jpg"><cite>Lead credit</cite></div>
        <div id="zephr-anchor">
          <div class="duet--article--article-body-component"><p>First editorial paragraph.</p></div>
          <div class="duet--article--article-body-component"><div class="duet--article--article-body-component" id="nested">
            <p>Last editorial paragraph.</p></div></div>
          <div class="duet--article--article-body-component"><div class="duet--article--product-card">
            <a href="https://shop.example/item">${'$'}249 at Shop</a><button>Read More</button></div></div>
          <div class="duet--article--article-body-component"><h5><a href="/review">Read the review</a>.</h5></div>
          <div class="duet--article--article-body-component"><div class="duet--article--related"><a href="/other">Related story</a></div></div>
          <div class="duet--article--article-body-component"><div style="display:none"><p>Hidden paragraph</p></div>
            <form><input type="email"><button>Sign Up</button></form><script>neverExecute()</script></div>
        </div></article>
    """.trimIndent()

    private fun ready(html: String) = ArticleExtractionPreparation.prepare(html, publicUrl).let {
        assertTrue("Expected eligible public source, got $it", it is ArticleExtractionPreparation.Result.Ready)
        it as ArticleExtractionPreparation.Result.Ready
    }

    private fun assertRestricted(html: String) = assertReason(html, ArticleExtractionPreparation.AccessReason.PUBLISHER_RESTRICTED)
    private fun assertReason(html: String, reason: ArticleExtractionPreparation.AccessReason) {
        assertEquals(ArticleExtractionPreparation.Result.RequiresBrowser(reason), ArticleExtractionPreparation.prepare(html, publicUrl))
    }

    // Synthetic body, with only the minimal widget structure observed on the public Verge fixture.
    // No copyrighted article text or full publisher pages are stored in the repository.
    private fun publicVergeFixture() = """
        <html><head><script type="application/ld+json">{"@type":"NewsArticle","isAccessibleForFree":true}</script></head><body>
        <article><span>by Example Author</span>
        <aside id="popover-example-author_byline_lead"><p>Posts from this author will be added to your daily email digest and your homepage feed.</p><button>Follow</button></aside>
        <div id="zephr-anchor"><div id="article-body" class="duet--article--article-body-component">
        <p class="editorial">First synthetic paragraph with <a href="../source">a source</a>.</p>
        <figure><img src="images/garden.jpg" alt="Garden"><figcaption>A synthetic garden caption.</figcaption></figure>
        <aside id="popover-example-breadcrumb"><p>Posts from this topic will be added to your daily email digest and your homepage feed.</p><button>Follow</button></aside>
        <form><div class="duet--cta--newsletter"><p>Email signup notice</p><input type="email"><button type="submit">Sign Up</button></div></form>
        <p class="editorial">最后一段，保留中文与标点。</p></div>
        <div class="tly2fw0"><span class="tly2fw2"><strong>Follow topics and authors</strong> to receive updates.</span>
        <ul><li id="follow-author-article_footer-example"><button>Example Author</button></li></ul></div>
        </div></article></body></html>
    """.trimIndent()
}
