package me.ash.reader.infrastructure.rss

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class NewsletterBodyPresentationTest {
    @Test fun blocksRemoteMediaAndStylesWithoutChangingDeliveredBody() {
        val html = """<article style="background:url(https://track.test/background)"><p>Delivered words <a href="https://publisher.test/read?token=secret" ping="https://track.test/click">Read online</a>.</p>
            <figure><picture><source srcset="https://track.test/pixel 1x"><img src="https://cdn.test/photo" alt="Portrait"></picture><figcaption>Original caption</figcaption></figure>
            <video poster="https://track.test/poster"><source src="https://track.test/video"></video><audio src="https://track.test/audio"></audio>
            <iframe src="https://track.test/frame" srcdoc="content"></iframe><object data="https://track.test/object"></object><embed src="https://track.test/embed">
            <svg><image href="https://track.test/svg"></image></svg><style>p{background:url(https://track.test/css)}</style><link rel="stylesheet" href="https://track.test/style">
            <input type="image" src="https://track.test/input"><p style="display:none">Hidden preview</p></article>"""
        val result = NewsletterBodyPresentation.withoutRemoteMedia(html).getOrThrow()
        val document = Jsoup.parseBodyFragment(result)
        assertTrue(document.text().contains("Delivered words"))
        assertTrue(document.text().contains("[Portrait]"))
        assertTrue(document.text().contains("Original caption"))
        assertFalse(document.text().contains("Hidden preview"))
        assertEquals("https://publisher.test/read?token=secret", document.selectFirst("a")!!.attr("href"))
        assertTrue(document.select("img,source,iframe,object,embed,video,audio,svg,style,link,[style],[src],[srcset],[poster],[background],[srcdoc],[ping]").isEmpty())
        assertFalse(result.contains("https://track.test"))
        assertTrue("Only a display copy changes", html.contains("https://track.test/pixel"))
    }

    @Test fun malformedAndOversizedBodiesFailWithoutLoadingOriginalResources() {
        val deep = "<div>".repeat(140) + "<img src='https://track.test/pixel'>" + "</div>".repeat(140)
        for (html in listOf(deep, "x".repeat(4 * 1024 * 1024 + 1))) {
            for (allowImages in listOf(false, true)) {
                val result = NewsletterBodyPresentation.withoutRemoteMedia(html, allowImages)
                assertTrue(result.isFailure)
                assertNull("A failure must not be a saveable article body", result.getOrNull())
                assertEquals("Newsletter exceeds reader display limits", result.exceptionOrNull()?.message)
            }
        }
    }

    @Test fun imageConsentAllowsOnlyImageSourcesAndKeepsOtherResourcesBlocked() {
        val html = """<p>Original words</p><img src="https://cdn.test/photo.png" srcset="/small.png 1x, https://cdn.test/large.png 2x, javascript:evil 3x" onerror="fetch('/confirm')" style="background:url('/track')">
            <img src="javascript:alert(1)"><img src="data:image/svg+xml;base64,PHN2Zz4="><img src="data:image/png;base64,YWJj">
            <iframe src="https://delivery.test/confirm"></iframe><meta http-equiv="refresh" content="0;url=https://delivery.test/unsubscribe">
            <link rel="prefetch" href="https://delivery.test/confirm"><style>@import url(https://track.test/style)</style>
            <object data="https://delivery.test/unsubscribe"></object><input type="image" src="https://track.test/input">
            <p style="display:none">Hidden preview</p><script>fetch('/confirm')</script>"""
        val result = NewsletterBodyPresentation.withoutRemoteMedia(html, allowImages = true).getOrThrow()
        val document = Jsoup.parseBodyFragment(result)
        val images = document.select("img")
        assertEquals("https://cdn.test/photo.png", images[0].attr("src"))
        assertEquals("/small.png 1x, https://cdn.test/large.png 2x", images[0].attr("srcset"))
        assertFalse(images[1].hasAttr("src"))
        assertFalse(images[2].hasAttr("src"))
        assertEquals("data:image/png;base64,YWJj", images[3].attr("src"))
        assertTrue(document.select("iframe,meta,object,script,style,link,[onerror],[style]").isEmpty())
        assertFalse(document.selectFirst("input")!!.hasAttr("src"))
        assertFalse(result.contains("delivery.test"))
        assertFalse(result.contains("track.test"))
        assertFalse(document.text().contains("Hidden preview"))
        assertTrue(html.contains("delivery.test/confirm"))
    }

    @Test fun privateUrlSummaryNeverIncludesPathQueryFragmentOrUserInfo() {
        val summary = newsletterFeedUrlSummary("https://mail:password@feeds.test/private-secret/feed?token=access-secret#fragment-secret")
        assertEquals("feeds.test · private feed URL", summary)
        assertEquals("Private newsletter feed URL", newsletterFeedUrlSummary("malformed private-secret"))
    }
}
