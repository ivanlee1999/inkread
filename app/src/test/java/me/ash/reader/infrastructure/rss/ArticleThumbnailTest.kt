package me.ash.reader.infrastructure.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticleThumbnailTest {
    private val articleUrl = "https://example.com/news/story"

    @Test fun resolvesRelativeAndProtocolRelativeImages() {
        assertEquals("https://example.com/photos/front.jpg", findHtmlThumbnail("<img src='../photos/front.jpg'>", articleUrl))
        assertEquals("https://cdn.example.com/front.jpg", findHtmlThumbnail("<img src='//cdn.example.com/front.jpg'>", articleUrl))
    }

    @Test fun lazySourceWinsOverPlaceholder() {
        assertEquals("https://example.com/front.jpg",
            findHtmlThumbnail("<img src='/blank.gif' data-src='/front.jpg'>", articleUrl))
        assertEquals("https://example.com/original.jpg",
            findHtmlThumbnail("<img src='data:image/gif;base64,AAAA' data-original='/original.jpg'>", articleUrl))
        assertEquals("https://example.com/responsive.jpg",
            findHtmlThumbnail("<img src='/blank.gif' data-srcset='/responsive.jpg 640w'>", articleUrl))
    }

    @Test fun findsSrcsetAndLazySrcsetWithoutDependingOnCommaWhitespace() {
        assertEquals("https://example.com/small.jpg",
            findHtmlThumbnail("<img srcset='/small.jpg 640w,/large.jpg 1280w'>", articleUrl))
        assertEquals("https://example.com/front.jpg",
            findHtmlThumbnail("<img data-srcset='data:image/png;base64,AAAA 1x, /front.jpg 2x'>", articleUrl))
    }

    @Test fun pictureSourceCanSupplyMissingImageSource() {
        assertEquals("https://example.com/front.webp",
            findHtmlThumbnail("<picture><source srcset='/front.webp 1x'><img alt='Front page'></picture>", articleUrl))
    }

    @Test fun preservesSignedAbsoluteUrlsAndCommasWithinSourceSetUrls() {
        val signed = "https://cdn.example.com/image?token=a%2Fb%2Bc&crop=1,2&Signature=X+Y%3D"
        assertEquals(signed, normalizeImageUrl(signed, articleUrl))
        assertEquals(signed, findHtmlThumbnail("<img src='${signed.replace("&", "&amp;")}'>", articleUrl))
        assertEquals(signed, findHtmlThumbnail("<img srcset='$signed 640w, /other.jpg 1280w'>", articleUrl))
    }

    @Test fun ignoresAudioEnclosuresAndFindsImageEnclosureOrInlinePhotograph() {
        assertEquals("https://example.com/front.jpg", findHtmlThumbnail(
            "<enclosure url='/episode.mp3' type='audio/mpeg'><enclosure type='image/jpeg' url='/front.jpg'>", articleUrl))
        assertEquals("https://example.com/inline.jpg", findHtmlThumbnail(
            "<enclosure url='/episode.mp3' type='audio/mpeg'><img src='/inline.jpg'>", articleUrl))
    }

    @Test fun skipsInvalidAndEmbeddedSourcesInsteadOfHidingLaterPhotograph() {
        assertEquals("https://example.com/front.jpg", findHtmlThumbnail(
            "<img src='null'><img src='data:image/png;base64,AAAA'><img src='javascript:bad()'><img src='/front.jpg'>", articleUrl))
        assertNull(findHtmlThumbnail("<img src='file:///private/photo.jpg'>", articleUrl))
        assertNull(findHtmlThumbnail("<img src='http://'>", articleUrl))
        assertNull(normalizeImageUrl("null", articleUrl))
        assertNull(normalizeImageUrl("https://example.com/\nimage.jpg", articleUrl))
        assertNull(normalizeImageUrl("/relative.jpg"))
    }

    @Test fun savedArticleUsesStoredImageThenSavedHtmlWithoutFetching() {
        val saved = "<img src='/from-html.jpg'>"
        assertEquals("https://example.com/stored.jpg", articleThumbnail("/stored.jpg", saved, articleUrl))
        assertEquals("https://example.com/from-html.jpg", articleThumbnail("null", saved, articleUrl))
        assertEquals("https://example.com/from-html.jpg", articleThumbnail("", saved, articleUrl))
        assertNull(articleThumbnail(null, "<p>Text only</p>", articleUrl))
    }

    @Test fun savedPublisherUrisCannotIntroduceLocalFilesOrContent() {
        listOf("file:///appcache/offline.png", "content://example/images/7", "android.resource://me.ash.reader/drawable/photo").forEach { local ->
            assertNull(articleThumbnail(local, null, articleUrl))
            assertEquals("https://example.com/front.jpg", articleThumbnail(local, "<img src='/front.jpg'>", articleUrl))
            assertNull(findHtmlThumbnail("<img src='$local'>", articleUrl))
        }
    }

    @Test fun legacyAudioEnclosureFallsBackButSignedOpaqueImageUrlRemainsIntact() {
        val html = "<img src='/front.jpg'>"
        assertEquals("https://example.com/front.jpg", articleThumbnail("https://example.com/episode.MP3?token=secret", html, articleUrl))
        assertEquals("https://example.com/front.jpg", articleThumbnail("/report.pdf", html, articleUrl))
        val signed = "https://cdn.example.com/render?name=episode.mp3&Signature=X%2FY+Z%3D"
        assertEquals(signed, articleThumbnail(signed, html, articleUrl))
    }
}
