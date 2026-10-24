package me.ash.reader.infrastructure.html

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device gate for Rhino API compatibility and packaged classpath JS resources. */
@RunWith(AndroidJUnit4::class)
class ModernReadabilitySmokeTest {
    @Test fun bundledEngineExtractsChineseShortArticleAndRelativeImage() {
        val html = """
            <html lang="zh"><head><title>中文短文章</title></head><body><article>
            <h1>中文短文章</h1>
            <p>第一段：这是一篇中文短文章，包含说明和标点符号。图片应该正确保留。</p>
            <figure><img src="../images/photo.jpg"><figcaption>图片说明</figcaption></figure>
            <p>最后一段：短文章不应该因为字数少而丢失，原有段落顺序需要保留。</p>
            <script>throw new Error("Publisher script must not execute");</script>
            </article></body></html>
        """.trimIndent()
        val result = checkNotNull(ModernReadability().extract(html, "https://example.org/news/story"))
        assertEquals("中文短文章", result.title)
        assertEquals("zh", result.lang)
        assertTrue(result.textContent.indexOf("第一段") >= 0)
        assertTrue(result.textContent.indexOf("最后一段") > result.textContent.indexOf("第一段"))
        assertFalse(result.content.contains("Publisher script"))
        val image = Jsoup.parseBodyFragment(result.content).selectFirst("img")
        assertEquals("https://example.org/images/photo.jpg", image?.attr("src"))
    }

    @Test fun instructionLimitWorksOnDevice() {
        try {
            ModernReadability(ModernReadability.Limits(maxInstructions = 1)).extract(
                "<html><body><article>" + "<p>Useful article content.</p>".repeat(20) + "</article></body></html>", "https://example.org"
            )
            fail("Expected bounded failure")
        } catch (e: ModernReadability.ExtractionException) {
            assertEquals(ModernReadability.Failure.INSTRUCTION_LIMIT, e.reason)
        }
    }
}
