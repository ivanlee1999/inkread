package me.ash.reader.infrastructure.rss

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BrowserTextImportTest {
    @Test fun acceptsShortChineseWithoutAWordCountThreshold() {
        val text = "今天有雨。"
        assertNull(BrowserTextImport.validate(text))
        assertEquals(text, Jsoup.parseBodyFragment(BrowserTextImport.toSafeHtml(text)).text())
    }

    @Test fun apparentHtmlScriptsAndImageUrlsRemainLiteralText() {
        val text = "<script>alert('x')</script></p><img src=\"https://tracker.example/pixel\"> & text"
        val html = BrowserTextImport.toSafeHtml(text)
        val body = Jsoup.parseBodyFragment(html).body()
        assertEquals(text, body.text())
        assertTrue(body.select("script,img,iframe,a,style").isEmpty())
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp; text"))
    }

    @Test fun preservesParagraphsAndLineBreaksWithoutImportingFormatting() {
        val body = Jsoup.parseBodyFragment(BrowserTextImport.toSafeHtml(
            "First line\r\nSecond line\r\n\r\nNext paragraph\rLast line"
        )).body()
        assertEquals(2, body.select("p").size)
        assertEquals(2, body.select("br").size)
        assertEquals("First line Second line", body.select("p")[0].text())
        assertEquals("Next paragraph Last line", body.select("p")[1].text())
    }

    @Test fun rejectsEmptyAndUrlOnlyPastesButAllowsUrlsWithinArticleText() {
        listOf("", " \n\t", "\u3000").forEach {
            assertEquals(BrowserTextImport.ValidationError.EMPTY, BrowserTextImport.validate(it))
        }
        listOf("https://example.com/article", "WWW.example.com/article",
            "https://example.com/a\nhttp://example.com/b", "<https://example.com/article>").forEach {
            assertEquals(BrowserTextImport.ValidationError.URL_ONLY, BrowserTextImport.validate(it))
        }
        assertNull(BrowserTextImport.validate("See https://example.com/article for context."))
    }

    @Test fun enforcesUtf8ByteBoundWithoutSilentlyTruncating() {
        val limit = BrowserTextImport.MAX_UTF8_BYTES
        assertNull(BrowserTextImport.validate("a".repeat(limit)))
        assertEquals(BrowserTextImport.ValidationError.TOO_LARGE,
            BrowserTextImport.validate("a".repeat(limit + 1)))
        assertNull(BrowserTextImport.validate("中".repeat(limit / 3)))
        assertEquals(BrowserTextImport.ValidationError.TOO_LARGE,
            BrowserTextImport.validate("中".repeat(limit / 3 + 1)))
        assertEquals(BrowserTextImport.ValidationError.TOO_LARGE,
            BrowserTextImport.validate("🙂".repeat(limit / 4 + 1)))
    }

    @Test fun conversionItselfRejectsInvalidImports() {
        listOf(" ", "https://example.com", "a".repeat(BrowserTextImport.MAX_UTF8_BYTES + 1)).forEach {
            try {
                BrowserTextImport.toSafeHtml(it)
                fail("Invalid text must not be converted")
            } catch (_: IllegalArgumentException) {
                // Expected independently of UI validation.
            }
        }
    }
}
