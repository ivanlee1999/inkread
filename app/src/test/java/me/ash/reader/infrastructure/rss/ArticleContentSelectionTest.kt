package me.ash.reader.infrastructure.rss

import org.junit.Assert.*
import org.junit.Test

class ArticleContentSelectionTest {
    private fun choose(feed: String, full: String?) = ArticleContentSelection.select(feed, full)
    @Test fun `failure keeps original server HTML and proxy images verbatim`() {
        val feed = "<p id='anchor'>中文新闻。</p><img src='https://rss.example/proxy?url=original'>"
        val result = ArticleContentSelection.select(feed, null, failed = true)
        assertEquals(ArticleContentSelection.Source.FEED, result.source)
        assertEquals(feed, result.html)
        assertEquals(ArticleContentSelection.Reason.EXTRACTION_FAILED, result.reason)
    }
    @Test fun `short legitimate article does not require a minimum word count`() {
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose("", "<p>已发布。</p>").source)
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose("", "<p>Done.</p>").source)
        assertEquals(ArticleContentSelection.Source.FEED, choose("<p>Done.</p>", "<p>Done.</p>").source)
    }
    @Test fun `Chinese full article extends truncated feed without whitespace words`() {
        val feed = "今天发布新的阅读器功能，让大家读得更加舒适。"
        assertEquals(ArticleContentSelection.Source.EXTRACTED,
            choose("<p>$feed</p>", "<p>$feed 更多内容介绍排版和离线阅读。</p>").source)
    }
    @Test fun `long complete server body wins over short or unrelated extraction`() {
        val feed = (1..20).joinToString("") { "<p>Paragraph $it explains reading comfortably with useful examples.</p>" }
        assertEquals(ArticleContentSelection.Source.FEED, choose(feed, "Subscribe now.").source)
        val unrelated = "A completely different story and unrelated navigation. ".repeat(40)
        assertEquals(ArticleContentSelection.Source.FEED, choose(feed, unrelated).source)
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose(feed, "$feed Additional paragraphs. ".repeat(2)).source)
    }
    @Test fun `equivalent extracted text retains original markup anchors and proxy URL`() {
        val feed = "<section id='chapter'><p>Original text.</p><img src='https://server/proxy/image'></section>"
        assertEquals(feed, choose(feed, "<article><p>Original text.</p></article>").html)
    }
    @Test fun `image only feed survives failure and empty documents stay unavailable`() {
        assertEquals(ArticleContentSelection.Source.FEED, choose("<img src='https://server/image'>", null).source)
        assertEquals(ArticleContentSelection.Source.UNAVAILABLE, choose(" ", "<p></p>").source)
    }
    @Test fun `long editorial summary does not reject a legitimate differently worded article`() {
        val summary = "A brief editorial overview of today's news and why it matters to readers. ".repeat(5)
        val full = "<p>The detailed report follows the development of a community garden and its history.</p>".repeat(30)
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose(summary, full).source)
    }
    @Test fun `same text with newly recovered photos is an improvement`() {
        assertEquals(ArticleContentSelection.Source.EXTRACTED,
            choose("<p>Short article.</p>", "<p>Short article.</p><img src='https://site/photo'>").source)
    }
    @Test fun `responsive variants count as one editorial picture`() {
        val feed = "<p>Short text.</p><picture><source srcset='a 1x'><source srcset='b 2x'><img src='c'></picture>"
        val extracted = "<p>Short text. An additional useful paragraph.</p><img src='c'>"
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose(feed, extracted).source)
    }
    @Test fun `cached full content uses same decision without any network dependency`() {
        val cached = "<p>Full original article with the rest of the story.</p>"
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose("<p>Full original article</p>", cached).source)
        assertEquals(ArticleContentSelection.Source.FEED, choose(cached, "<p>Original</p>").source)
    }
}
