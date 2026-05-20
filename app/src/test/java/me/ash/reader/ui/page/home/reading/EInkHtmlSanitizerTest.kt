package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EInkHtmlSanitizerTest {

    @Test
    fun `appendSanitizedHtml strips scripts and event handlers`() {
        val out = StringBuilder()

        appendSanitizedHtml(
            out,
            """
            <p onclick="alert(1)" onmouseover='evil()' onload=evil()>Safe</p>
            <script>alert('owned')</script>
            """.trimIndent(),
        )

        val sanitized = out.toString()
        assertEquals("<p   >Safe</p>\n", sanitized)
        assertFalse(sanitized.contains("script", ignoreCase = true))
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.contains("onmouseover", ignoreCase = true))
        assertFalse(sanitized.contains("onload", ignoreCase = true))
    }

    @Test
    fun `appendSanitizedHtml decodes entity-obfuscated javascript schemes before stripping`() {
        val out = StringBuilder()

        appendSanitizedHtml(out, "<a href=\"java&#x73;cript:alert(1)\">bad</a>")

        assertEquals("<a href=\"alert(1)\">bad</a>", out.toString())
    }

    @Test
    fun `appendSanitizedHtml writes large articles in bounded chunks`() {
        val out = TrackingAppendable()
        val html = "<p>${"a".repeat(20_000)}</p>"

        appendSanitizedHtml(out, html)

        assertEquals(html, out.toString())
        assertTrue(out.maxAppendLength < html.length)
    }

    private class TrackingAppendable : Appendable {
        private val delegate = StringBuilder()
        var maxAppendLength = 0
            private set

        override fun append(csq: CharSequence?): Appendable {
            val text = csq?.toString() ?: "null"
            maxAppendLength = maxOf(maxAppendLength, text.length)
            delegate.append(text)
            return this
        }

        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
            val text = (csq ?: "null").subSequence(start, end).toString()
            maxAppendLength = maxOf(maxAppendLength, text.length)
            delegate.append(text)
            return this
        }

        override fun append(c: Char): Appendable {
            maxAppendLength = maxOf(maxAppendLength, 1)
            delegate.append(c)
            return this
        }

        override fun toString(): String = delegate.toString()
    }
}
