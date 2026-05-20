package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
