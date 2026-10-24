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
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose(feed, "$feed<p>An additional paragraph continues the article with new findings.</p>").source)
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
        val full = (1..30).joinToString("") { "<p>Part $it of the detailed report follows the development of a community garden and its history.</p>" }
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

    private fun body(count: Int = 5): String = (1..count).joinToString("") {
        "<p>Section $it reports original findings from a community project, describing the evidence, " +
            "the participants and the practical consequences in enough detail to be useful.</p>"
    }

    @Test fun `cleaner hidden topic UI does not count as missing body text`() {
        val article = body()
        val prompts = (1..12).joinToString("") {
            "<aside aria-hidden='true'><p>Follow topic $it and add its updates to your daily digest.</p>" +
                "<img src='author-$it' width='125' height='125'></aside>"
        }
        val original = "<section id='original'>$article$prompts</section>"
        val cleaned = "<article>$article</article>"
        val selection = choose(original, cleaned)
        assertEquals(ArticleContentSelection.Source.EXTRACTED, selection.source)
        assertEquals(cleaned, selection.html)
    }

    @Test fun `hidden UI and extra length cannot mask a missing editorial paragraph`() {
        val original = body()
        val incomplete = body(4) + "<nav>" + "Visit another topic. ".repeat(100) + "</nav>"
        assertEquals(original, choose(original, incomplete).html)
    }

    @Test fun `one missing paragraph is rejected even in a long nearly complete article`() {
        val original = body(30)
        val missingMiddle = original.replace(Regex("<p>Section 15 .*?</p>"), "")
        assertEquals(ArticleContentSelection.Source.FEED, choose(original, missingMiddle).source)
    }

    @Test fun `short middle and final paragraphs remain part of the body`() {
        val first = body(3)
        val middle = "<p>Correction: the meeting is Friday.</p>"
        val ending = "<p>Applications close tomorrow.</p>"
        val article = first + middle + body(5).replace(first, "") + ending
        assertEquals(article, choose(article, article.replace(middle, "")).html)
        assertEquals(article, choose(article, article.replace(ending, "")).html)
    }

    @Test fun `equivalent split or merged paragraphs can retain all editorial text`() {
        val original = body() + "<aside hidden><p>Follow updates.</p></aside>"
        val merged = "<p>" + body().replace("<p>", "").replace("</p>", " ") + "</p>"
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose(original, merged).source)
    }

    @Test fun `reordered paragraphs do not replace the original reading order`() {
        val original = body()
        val reordered = Regex("<p>.*?</p>").findAll(original).map { it.value }.toList().reversed().joinToString("")
        assertEquals(original, choose(original, reordered).html)
    }

    @Test fun `body links and retailer labels cannot disappear during cleanup`() {
        val article = body().replace("the participants", "<a href='https://shop.example/item'>the participants</a>")
        val noLinks = article.replace("<a href='https://shop.example/item'>", "").replace("</a>", "")
        val noLabels = article.replace("<a href='https://shop.example/item'>the participants</a>", "")
        assertEquals(article, choose(article, noLinks + "<img src='new-photo'>").html)
        assertEquals(article, choose(article, noLabels + body(2)).html)
    }

    @Test fun `unstructured same text must retain links even when a photo is added`() {
        val original = "Read <a href='https://example.com/report'>the report</a>."
        assertEquals(original, choose(original, "Read the report.<img src='new-photo'>").html)
    }

    @Test fun `avatars do not compensate for a missing article photo`() {
        val original = body() + "<figure><img src='editorial-photo'></figure>"
        val candidate = body() + (1..8).joinToString("") { "<img class='author-avatar' src='person-$it'>" }
        assertEquals(original, choose(original, candidate).html)
    }

    @Test fun `tiny avatars alone are not newly recovered article images`() {
        val original = "<p>Brief news.</p>"
        assertEquals(original, choose(original, original + "<img class='avatar' width='36' height='36' src='avatar'>").html)
    }

    @Test fun `small figures and unknown dimension photos remain protected`() {
        for (image in listOf("<figure><img width='32' height='32' src='pixel-art'></figure>", "<img width='32' height='32' src='unmarked-art'>", "<img src='photo'>")) {
            val original = body() + image
            assertEquals(original, choose(original, body() + body(2)).html)
        }
    }

    @Test fun `duplicate responsive images count only once`() {
        val original = body() + "<img src='same-photo'><img src='same-photo'>"
        val candidate = body() + "<img src='same-photo'><p>A further useful update.</p>"
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose(original, candidate).source)
    }

    @Test fun `source only pictures are retained as images`() {
        val original = body() + "<picture><source srcset='photo.webp 1x, photo-large.webp 2x'></picture>"
        assertEquals(original, choose(original, body() + "<p>An extra paragraph.</p>").html)
    }

    @Test fun `repeating the article or appending a link cloud is not an improvement`() {
        val original = body()
        assertEquals(original, choose(original, original.repeat(3)).html)
        val links = (1..100).joinToString("") { "<a href='https://example.com/$it'>Another long unrelated destination $it</a>" }
        assertEquals(original, choose(original, original + links).html)
    }

    @Test fun `an extraction marked failed cannot replace existing source`() {
        val original = "<p>Original brief.</p>"
        val result = ArticleContentSelection.select(original, body(), failed = true)
        assertEquals(original, result.html)
        assertEquals(ArticleContentSelection.Reason.EXTRACTION_FAILED, result.reason)
    }

    @Test fun `comparison budgets preserve original rather than trusting partial measurements`() {
        val original = "<p id='keep'>Original brief.</p>"
        val tooManyBlocks = body(300)
        val tooMuchHtml = " ".repeat(2 * 1024 * 1024 + 1)
        val tooMuchText = "<p>" + "x".repeat(128 * 1024 + 1) + "</p>"
        for (candidate in listOf(tooManyBlocks, tooMuchHtml, tooMuchText)) {
            assertEquals(original, choose(original, candidate).html)
            assertFalse(ArticleContentSelection.isLikelyComplete(candidate))
        }
        assertEquals(tooManyBlocks, choose(tooManyBlocks, body()).html)
    }

    @Test fun `short Chinese and English paragraphs are protected without a word threshold`() {
        for (paragraphs in listOf(listOf("现在开放申请。", "明天截止。"), listOf("Applications are open.", "They close tomorrow."))) {
            val original = paragraphs.joinToString("") { "<p>$it</p>" }
            assertEquals(original, choose(original, "<p>${paragraphs.first()}</p>").html)
            assertEquals(ArticleContentSelection.Source.EXTRACTED,
                choose(original, original + "<p>Update: confirmed.</p>").source)
        }
    }

    @Test fun `complete source hint requires substantial distinct paragraphs not images or summaries`() {
        assertTrue(ArticleContentSelection.isLikelyComplete(body(12)))
        assertFalse(ArticleContentSelection.isLikelyComplete(body(2)))
        assertFalse(ArticleContentSelection.isLikelyComplete("<p>" + "A detailed summary. ".repeat(150) + "</p>"))
        assertFalse(ArticleContentSelection.isLikelyComplete("<img src='photo'>".repeat(8)))
        assertFalse(ArticleContentSelection.isLikelyComplete(body(1).repeat(12)))
    }

    @Test fun `complete source hint is language independent`() {
        val chinese = (1..12).joinToString("") {
            "<p>第 $it 段。" + "这篇报道详细介绍社区活动的背景和参与方式，并解释计划如何改善大家的日常生活。".repeat(4) + "</p>"
        }
        assertTrue(ArticleContentSelection.isLikelyComplete(chinese))
    }

    @Test fun `read more links truncation and gates keep automatic extraction eligible`() {
        for (ending in listOf(
            "<a href='https://example.com/full'>Read more</a>", "<p>Continue reading</p>",
            "<p>Subscribe to read the full article</p>", "<p>阅读全文</p>",
            "<p>繼續閱讀</p>", "<p>To be continued...</p>", "<p>尚未结束…</p>"
        )) assertFalse(ending, ArticleContentSelection.isLikelyComplete(body(12) + ending))
    }

    @Test fun `unique short introductions are retained before substantial paragraphs`() {
        val introduction = "<p>The project begins tomorrow morning.</p>"
        val original = introduction + body()
        assertEquals(original, choose(original, body() + "<img src='new-photo'>").html)
    }

    @Test fun `an intentional repeated passage cannot be removed for an extra photo`() {
        val original = body() + "<p>Remember why this matters.</p><p>Remember why this matters.</p>"
        val candidate = body() + "<p>Remember why this matters.</p><img src='new-photo'>"
        assertEquals(original, choose(original, candidate).html)
    }

    @Test fun `expanded article can omit a linked terminal feed continuation`() {
        for (label in listOf("Read more", "Continue reading", "Read the full article", "阅读全文")) {
            val feed = body(1) + "<p><a href='https://example.com/full'>$label</a></p>"
            assertEquals(label, ArticleContentSelection.Source.EXTRACTED, choose(feed, body()).source)
        }
    }

    @Test fun `expanded article can omit terminal WordPress attribution without dropping body links`() {
        val footer = "<p>The post <a href='https://example.com/full'>Community report</a> appeared first on " +
            "<a href='https://example.com'>Example News</a>.</p>"
        val feed = body(1) + footer
        assertEquals(ArticleContentSelection.Source.EXTRACTED, choose(feed, body()).source)
        val linkedBody = body(1).replace("the participants", "<a href='https://example.com/evidence'>the participants</a>")
        assertEquals(ArticleContentSelection.Source.FEED, choose(linkedBody + footer, body()).source)
    }

    @Test fun `continuation wording is not ignored inside the body or a substantial paragraph`() {
        val link = "<p><a href='https://example.com/evidence'>Read more</a></p>"
        val original = body(1) + link + body(3).replace(body(1), "")
        assertEquals(original, choose(original, body(3)).html)
        val explanation = "<p>To learn about the evidence and the methods used by the participants, " +
            "<a href='https://example.com/evidence'>read more</a> about this detailed investigation.</p>"
        val article = body() + explanation
        assertEquals(article, choose(article, body() + "<img src='new-photo'>").html)
    }

    @Test fun `a footer before non paragraph article text is not a terminal feed footer`() {
        val footer = "<p><a href='https://example.com/extra'>Continue reading</a></p>"
        val original = body(1) + footer + "<div>The article continues with another important finding.</div>"
        assertEquals(original, choose(original, body()).html)
    }
}
