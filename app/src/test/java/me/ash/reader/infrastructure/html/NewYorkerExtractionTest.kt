package me.ash.reader.infrastructure.html

import me.ash.reader.infrastructure.rss.ArticleContentSelection
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Synthetic prose; markup reflects ordinary publicly accessible New Yorker article documents. */
class NewYorkerExtractionTest {
    private val url = "https://www.newyorker.com/news/synthetic-public-story"
    private val paragraph = "The community garden opened this week, with newly planted trees and paths for visitors. " +
        "The report describes the planting choices and the plans for the next season. "
    private val dek = "This editorial summary describes the context of the report and the questions that it raises, " +
        "using different wording from the body and enough detail to reproduce a substantial RSS description."
    private fun metadata(value: String = "true", articleUrl: String = url) =
        """<script type="application/ld+json">{"@type":"NewsArticle","url":"$articleUrl","isAccessibleForFree":$value,"articleBody":"METADATA_BODY_MUST_NOT_BE_USED"}</script>"""
    private fun page(body: String = "<p class='paywall'>FIRST $paragraph</p><p class='paywall'>LAST $paragraph</p>",
        head: String = metadata(), lead: String = "<figure><img src='/garden.jpg'><figcaption>Editorial image credit.</figcaption></figure>",
        caption: String = "") = """<html><head><title>Public garden report</title>$head</head><body>
        <nav>Site navigation</nav><header data-testid="ContentHeaderContainer"><div class="ContentHeaderDek-synthetic">$dek</div>
        <div data-testid="ContentHeaderLeadAsset">$lead</div>$caption</header>
        <div data-testid="BodyWrapper"><div class="body__inner-container">$body</div></div></body></html>"""
    private fun ready(html: String) = ArticleExtractionPreparation.prepare(html, url).let {
        assertTrue("Expected eligible public source, got ${it.javaClass.simpleName}", it is ArticleExtractionPreparation.Result.Ready)
        it as ArticleExtractionPreparation.Result.Ready
    }
    private fun gate(html: String, target: String = url) = assertEquals(
        ArticleExtractionPreparation.Result.RequiresBrowser(ArticleExtractionPreparation.AccessReason.ACCESS_GATE),
        ArticleExtractionPreparation.prepare(html, target))

    @Test fun publicEditorialMeteringClassesPreserveProseHeadingsAndLists() {
        val prepared = ready(page("<p class='paywall'>FIRST $paragraph</p>" +
            "<h2 class='paywall'>Editorial heading</h2><ul class='paywall'><li>A cited reading.</li></ul>" +
            "<p class='paywall'><a href='/reading'>Read the related essay.</a></p><p class='paywall'>LAST $paragraph</p>"))
        val result = checkNotNull(ModernReadability().extract(prepared.html, url, preservedContent = prepared.articleHtml))
        val body = Jsoup.parseBodyFragment(result.content)
        assertEquals("Editorial heading", body.selectFirst("h2")!!.text())
        assertEquals("A cited reading.", body.selectFirst("li")!!.text())
        assertEquals("https://www.newyorker.com/reading", body.selectFirst("p a")!!.attr("href"))
        assertEquals("https://www.newyorker.com/garden.jpg", body.selectFirst("img")!!.attr("src"))
        assertEquals("Editorial image credit.", body.selectFirst("figcaption")!!.text())
        assertTrue(result.textContent.indexOf("FIRST") < result.textContent.indexOf("Read the related essay."))
        assertTrue(result.textContent.indexOf("Read the related essay.") < result.textContent.indexOf("LAST"))
        assertFalse(result.textContent.contains("Site navigation"))
        assertFalse(result.content.contains("METADATA_BODY_MUST_NOT_BE_USED"))
        // The differently worded RSS dek must be retained without weakening the quality policy.
        assertEquals(ArticleContentSelection.Source.EXTRACTED, ArticleContentSelection.select(dek, result.content).source)
    }

    @Test fun accessExceptionRequiresExactPublisherAndMatchingMainBooleanFreeMetadata() {
        gate(page(head = ""))
        gate(page(head = metadata("\"true\"")))
        gate(page(head = metadata(articleUrl = "https://www.newyorker.com/news/another-story")))
        gate(page(head = metadata(articleUrl = "$url?different=article")))
        gate(page(), "https://www.newyorker.com.example.org/news/synthetic-public-story")
        gate(page(head = metadata() + metadata())) // Ambiguous main article declarations.
    }

    @Test fun restrictedMetadataAlwaysWinsBeforeMeteringClassException() {
        listOf("false", "\"false\"").forEach { value ->
            assertEquals(ArticleExtractionPreparation.Result.RequiresBrowser(ArticleExtractionPreparation.AccessReason.PUBLISHER_RESTRICTED),
                ArticleExtractionPreparation.prepare(page(head = metadata(value)), url))
        }
        assertEquals(ArticleExtractionPreparation.Result.RequiresBrowser(ArticleExtractionPreparation.AccessReason.PUBLISHER_RESTRICTED),
            ArticleExtractionPreparation.prepare(page(head = metadata() +
                "<script type='application/ld+json'>{\"hasPart\":{\"isAccessibleForFree\":false}}</script>"), url))
    }

    @Test fun realBarrierSelectorsRemainBlockedInsideAndOutsideEditorialContainer() {
        listOf("<div class='paywall'>Membership required.</div>", "<p id='paywall'>Membership required.</p>",
            "<p data-testid='paywall'>Membership required.</p>", "<p class='paywall-modal'>Membership required.</p>",
            "<p data-paywall='TRUE'>Membership required.</p>",
            "<p class='paywall' data-paywall=' TRUE '>Membership required.</p>").forEach { barrier ->
            gate(page(body = "<p class='paywall'>$paragraph</p>$barrier"))
        }
        gate(page() + "<p class='paywall'>Outside the editorial body.</p>")
    }

    @Test fun subscriptionNoticesRemainBlockedInEveryExemptEditorialTextTag() {
        listOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "figure", "figcaption").forEach { tag ->
            gate(page(body = "<p class='paywall'>$paragraph</p><$tag class='paywall'>Subscribe to continue reading.</$tag>"))
        }
    }

    @Test fun browserChallengesRemainBlockedInEveryExemptEditorialTextTag() {
        listOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "figure", "figcaption").forEach { tag ->
            assertEquals(ArticleExtractionPreparation.Result.RequiresBrowser(ArticleExtractionPreparation.AccessReason.BROWSER_CHALLENGE),
                ArticleExtractionPreparation.prepare(page(body = "<p class='paywall'>$paragraph</p>" +
                    "<$tag class='paywall'>Verify you are human.</$tag>"), url))
        }
    }

    @Test fun longSubscriptionAndChallengeNoticesCannotHideBehindEditorialMeteringMarkers() {
        val tail = " Additional access information.".repeat(30)
        listOf("p", "ul", "ol", "li", "blockquote", "figure", "figcaption").forEach { tag ->
            gate(page(body = "<p class='paywall'>$paragraph</p>" +
                "<$tag class='paywall'>Subscribe to continue reading.$tail</$tag>"))
            assertEquals(ArticleExtractionPreparation.Result.RequiresBrowser(ArticleExtractionPreparation.AccessReason.BROWSER_CHALLENGE),
                ArticleExtractionPreparation.prepare(page(body = "<p class='paywall'>$paragraph</p>" +
                    "<$tag class='paywall'>Verify you are human.$tail</$tag>"), url))
        }
        gate(page(body = "<p class='paywall'>$paragraph</p><ul class='paywall'><li>Subscribe to continue reading.$tail</li></ul>"))
        gate(page(body = "<p class='paywall'>$paragraph</p><blockquote class='paywall'><p>Subscribe to continue reading.$tail</p></blockquote>"))
    }

    @Test fun hiddenBodiesAndNestedContainersDoNotLeakOrDuplicateText() {
        val body = "<p class='paywall'>FIRST $paragraph</p><div class='body__inner-container'>" +
            "<p class='paywall'>NESTED $paragraph</p></div>" +
            "<div hidden><p class='paywall'>HIDDEN_BODY</p></div><script>PAGE_SCRIPT</script><form>Signup UI</form>"
        val prepared = ready(page(body))
        val candidate = Jsoup.parseBodyFragment(checkNotNull(prepared.articleHtml))
        assertEquals(1, candidate.select("p").count { it.text().startsWith("NESTED") })
        assertFalse(candidate.text().contains("HIDDEN_BODY"))
        assertFalse(candidate.text().contains("Signup UI"))
        assertTrue(candidate.select("script,form").isEmpty())
    }

    @Test fun unknownPublicLayoutKeepsGenericFallbackAndNoMetadataArticleBodyRecovery() {
        val unknown = page(body = "<p>A short visible report.</p>").replace("body__inner-container", "different-layout")
        assertNull(ready(unknown).articleHtml)
        val empty = page(body = "<hr class='paywall'>")
        assertNull(ready(empty).articleHtml)
    }

    @Test fun splitScreenPictureAndSiblingCreditRetainThePhotoRequiredByTheServerEnclosureComparison() {
        val prepared = ready(page(lead = "<span class='responsive-asset'><picture>" +
            "<source srcset='/garden-wide.jpg 2x'><img src='/garden.jpg'></picture></span>",
            caption = "<div data-testid='caption-wrapper'><span class='caption__credit'>Visible editorial photo credit.</span></div>")
            .replace("data-testid=\"ContentHeaderContainer\"", "data-testid=\"SplitScreenContentHeaderWrapper\""))
        val result = checkNotNull(ModernReadability().extract(prepared.html, url, preservedContent = prepared.articleHtml))
        val body = Jsoup.parseBodyFragment(result.content)
        assertEquals(1, body.select("img").size)
        assertEquals("https://www.newyorker.com/garden.jpg", body.selectFirst("img")!!.attr("src"))
        assertEquals("https://www.newyorker.com/garden-wide.jpg 2x", body.selectFirst("source")!!.attr("srcset"))
        assertEquals("Visible editorial photo credit.", body.selectFirst("figcaption")!!.text())
        assertTrue("Split-screen header without ContentHeaderContainer retains its visible RSS dek", result.textContent.contains(dek))
        val serverFeed = "<p>$dek</p><figure class='enclosure'><p class='enclosure-content'>" +
            "<img class='enclosure-thumbnail' src='https://reader.example.org/article-img-proxy.php?url=garden.jpg'></p></figure>"
        assertEquals(ArticleContentSelection.Source.EXTRACTED, ArticleContentSelection.select(serverFeed, result.content).source)
        // Keep the image-loss policy: missing that photo must reproduce the old feed-only verdict.
        body.select("figure").remove()
        assertEquals(ArticleContentSelection.Source.FEED, ArticleContentSelection.select(serverFeed, body.body().html()).source)
    }

    @Test fun bareLazyHeaderImageUsesOnlyItsVisibleScopedCredit() {
        val prepared = ready(page(lead = "<img data-original='/garden.jpg'>",
            caption = "<div hidden data-testid='caption-wrapper'>HIDDEN CREDIT</div>" +
                "<div data-testid='caption-wrapper'>Visible scoped credit.</div>") +
            "<div data-testid='caption-wrapper'>OUTSIDE HEADER</div>")
        val result = checkNotNull(ModernReadability().extract(prepared.html, url, preservedContent = prepared.articleHtml))
        val body = Jsoup.parseBodyFragment(result.content)
        assertEquals("https://www.newyorker.com/garden.jpg", body.selectFirst("img")!!.attr("src"))
        assertEquals("Visible scoped credit.", body.selectFirst("figcaption")!!.text())
        assertFalse(result.textContent.contains("HIDDEN CREDIT"))
        assertFalse(result.textContent.contains("OUTSIDE HEADER"))
    }

    @Test fun hiddenLeadMediaAndCreditsNeverEnterThePreservedArticle() {
        val prepared = ready(page(lead = "<picture hidden><img src='/hidden.jpg'></picture>",
            caption = "<div data-testid='caption-wrapper'>Unused image credit.</div>"))
        val result = checkNotNull(ModernReadability().extract(prepared.html, url, preservedContent = prepared.articleHtml))
        assertTrue(Jsoup.parseBodyFragment(result.content).select("img,picture,figcaption").isEmpty())
        assertFalse(result.textContent.contains("Unused image credit."))
    }
}
