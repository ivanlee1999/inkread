package me.ash.reader.ui.page.home.reading

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EInkHitTestTest {

    /** Mimics what `evaluateJavascript` hands back: the JS string, JSON-quoted. */
    private fun evaluated(payload: String) = "\"$payload\""

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    @Test
    fun `link hit is parsed`() {
        val raw = evaluated("link|${enc("https://example.com/post?a=1&b=2")}|")

        val hit = parseHitTestResult(raw)

        assertEquals(HIT_LINK, hit!!.type)
        assertEquals("https://example.com/post?a=1&b=2", hit.url)
        assertEquals("", hit.alt)
    }

    @Test
    fun `image hit keeps its alt text`() {
        val raw = evaluated("image|${enc("https://cdn.example.com/a.png")}|${enc("A chart of things")}")

        val hit = parseHitTestResult(raw)

        assertEquals(HIT_IMAGE, hit!!.type)
        assertEquals("https://cdn.example.com/a.png", hit.url)
        assertEquals("A chart of things", hit.alt)
    }

    @Test
    fun `non-ascii alt text survives the round trip`() {
        val raw = evaluated("image|${enc("https://example.com/a.png")}|${enc("图表 · 2026")}")

        assertEquals("图表 · 2026", parseHitTestResult(raw)!!.alt)
    }

    @Test
    fun `a url containing the field separator is not split`() {
        val url = "https://example.com/a|b?q=x|y"
        val raw = evaluated("link|${enc(url)}|")

        assertEquals(url, parseHitTestResult(raw)!!.url)
    }

    @Test
    fun `a miss returns null`() {
        assertNull(parseHitTestResult(evaluated("")))
        assertNull(parseHitTestResult(""))
        assertNull(parseHitTestResult(null))
    }

    @Test
    fun `a failed evaluation returns null`() {
        assertNull(parseHitTestResult("null"))
    }

    @Test
    fun `malformed payloads return null`() {
        assertNull(parseHitTestResult(evaluated("link")))
        assertNull(parseHitTestResult(evaluated("link|${enc("https://example.com")}")))
        assertNull(parseHitTestResult(evaluated("link|a|b|c")))
    }

    @Test
    fun `unknown hit types are rejected`() {
        assertNull(parseHitTestResult(evaluated("script|${enc("https://example.com")}|")))
    }

    @Test
    fun `non-web schemes are rejected`() {
        assertNull(parseHitTestResult(evaluated("link|${enc("javascript:alert(1)")}|")))
        assertNull(parseHitTestResult(evaluated("link|${enc("file:///etc/passwd")}|")))
        assertNull(parseHitTestResult(evaluated("link|${enc("intent://evil")}|")))
    }

    @Test
    fun `urls resolved against the local article document are rejected`() {
        // A bare fragment link, or a relative image path, resolves against the
        // synthetic origin the article is served from — not a real destination.
        assertNull(
            parseHitTestResult(
                evaluated("link|${enc("${EINK_DOCUMENT_ORIGIN}eink/article-1.html#footnote")}|")
            )
        )
        assertNull(
            parseHitTestResult(
                evaluated("image|${enc("${EINK_DOCUMENT_ORIGIN}eink/images/a.png")}|")
            )
        )
    }

    @Test
    fun `plain http is allowed`() {
        assertEquals(
            "http://example.com/a",
            parseHitTestResult(evaluated("link|${enc("http://example.com/a")}|"))!!.url,
        )
    }
}
