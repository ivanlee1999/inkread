package me.ash.reader.infrastructure.rss

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Only synthetic feed URLs reach this local server; article and delivery links are inert data. */
class NewsletterFeedTest {
    private data class Document(val body: String, val type: String)
    private val documents = ConcurrentHashMap<String, Document>()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var helper: RssHelper

    @Before fun setUp() = runBlocking {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = requireNotNull(request.requestUrl).encodedPath
                    requests.add(path)
                    val document = documents[path] ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setHeader("Content-Type", document.type).setBody(document.body)
                }
            }
            start()
        }
        client = OkHttpClient()
        helper = spy(RssHelper(mock(), Dispatchers.IO, client))
        // Prevent a regression from probing the host without its local fixture port, then verify
        // that newsletter search never asked for icon discovery at all.
        doReturn(null).whenever(helper).queryRssIconLink(any())
        Unit
    }

    @After fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    @Test fun rssNewsletterRetainsDeliveredBodyWithoutFollowingArticleOrDeliveryLinks() = runBlocking {
        val link = server.url("/tracking/article?recipient=synthetic").toString()
        val body = body()
        documents["/newsletter/rss"] = Document("""
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel><title>Fixture newsletter</title><link>${server.url("/publisher")}</link>
                <description>A synthetic delivered newsletter</description>
                <image><url>${server.url("/tracking/icon.png")}</url><title>Icon</title><link>${server.url("/publisher")}</link></image>
                <item><guid isPermaLink="false">rss-1</guid><title>Delivered issue</title><link>$link</link>
                  <description>Only the teaser.</description><content:encoded><![CDATA[$body]]></content:encoded>
                </item>
              </channel>
            </rss>
        """.trimIndent(), "application/rss+xml; charset=UTF-8")

        val result = helper.searchFeed(server.url("/newsletter/rss?token=synthetic-secret").toString(), newsletter = true)
        val article = helper.buildArticleFromSyndEntry(newsletter(result.feedLink), 1, result.feed.entries.single())

        assertEquals(body, article.rawDescription)
        assertEquals(link, article.link)
        assertEquals("Delivered issue", article.title)
        assertNull(result.feed.icon.url)
        assertEquals(listOf("/newsletter/rss"), requests.toList())
        verify(helper, never()).queryRssIconLink(any())
    }

    @Test fun atomNewsletterRetainsHtmlBodyWithoutFetchingFeedIconOrStory() = runBlocking {
        val link = server.url("/tracking/atom-article?recipient=synthetic").toString()
        val body = body()
        documents["/newsletter/atom"] = Document("""
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>urn:fixture:newsletter</id><title>Fixture newsletter</title><updated>2026-10-26T00:00:00Z</updated>
              <icon>${server.url("/tracking/icon.png")}</icon>
              <entry><id>urn:fixture:issue-1</id><title>Delivered issue</title><updated>2026-10-26T00:00:00Z</updated>
                <link rel="alternate" href="$link"/><summary>Only the teaser.</summary>
                <content type="html"><![CDATA[$body]]></content>
              </entry>
            </feed>
        """.trimIndent(), "application/atom+xml; charset=UTF-8")

        val result = helper.searchFeed(server.url("/newsletter/atom").toString(), newsletter = true)
        val article = helper.buildArticleFromSyndEntry(newsletter(result.feedLink), 1, result.feed.entries.single())

        assertEquals(body, article.rawDescription)
        assertEquals(link, article.link)
        assertNull(result.feed.icon.url)
        assertEquals(listOf("/newsletter/atom"), requests.toList())
        verify(helper, never()).queryRssIconLink(any())
    }

    @Test fun newsletterHtmlLandingPageDoesNotFollowAnAlternateFeed() = runBlocking {
        documents["/newsletter/landing"] = Document(
            "<html><head><link rel='alternate' type='application/rss+xml' href='/confirm-subscription'></head><body>Confirm delivery</body></html>",
            "text/html; charset=UTF-8",
        )

        try {
            helper.searchFeed(server.url("/newsletter/landing").toString(), newsletter = true)
            fail("Newsletter entry requires an actual RSS or Atom document")
        } catch (expected: IOException) {
            assertEquals("Enter a direct RSS or Atom newsletter feed URL", expected.message)
        }

        assertEquals(listOf("/newsletter/landing"), requests.toList())
        verify(helper, never()).queryRssIconLink(any())
    }

    @Test fun newsletterFetchRejectsMoreThanFourMiBWithoutDiscovery() = runBlocking {
        documents["/newsletter/oversize"] = Document("x".repeat(4 * 1024 * 1024 + 1), "application/rss+xml")

        try {
            helper.searchFeed(server.url("/newsletter/oversize").toString(), newsletter = true)
            fail("An oversized newsletter feed must not reach the parser")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("exceeds"))
        }

        assertEquals(listOf("/newsletter/oversize"), requests.toList())
        verify(helper, never()).queryRssIconLink(any())
    }

    @Test fun newsletterWithoutImagesDoesNotCreateAnArticleMetadataRequest() {
        val request = articleImageRequest(null, "<p>The delivered issue is complete.</p>",
            "https://example.com/confirm?token=synthetic", allowMetadataLookup = false)

        assertNull(request)
    }

    private fun newsletter(url: String) = Feed(
        id = "1:newsletter", name = "Fixture newsletter", url = url, groupId = "1:group",
        accountId = 1, isNewsletter = true,
    )

    private fun body() = "<p>Delivered newsletter body: 中文 and English remain intact.</p>" +
        "<p><a href='${server.url("/confirm-subscription")}'>Confirm</a> " +
        "<a href='${server.url("/unsubscribe")}'>Unsubscribe</a></p>" +
        "<img src='${server.url("/tracking/pixel.png")}' width='1' height='1'>"
}
