package me.ash.reader.infrastructure.rss

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

class RssFeedDiscoveryTest {
    private data class Document(
        val body: String = "",
        val type: String = "text/html; charset=UTF-8",
        val location: String? = null,
        val code: Int = if (location == null) 200 else 302,
    )

    private val documents = ConcurrentHashMap<String, Document>()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var helper: RssHelper

    @Before fun setUp() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                requests.add(path)
                val document = documents[path] ?: Document(code = 404)
                return MockResponse()
                    .setResponseCode(document.code)
                    .setHeader("Content-Type", document.type)
                    .setBody(document.body)
                    .apply { document.location?.let { setHeader("Location", it) } }
            }
        }
        server.start()
        client = OkHttpClient()
        helper = spy(RssHelper(mock(), Dispatchers.IO, client))
        // Icon lookup is separate from discovery and would omit this fixture's port.
        whenever(helper.queryRssIconLink(any())).thenReturn(null)
        Unit
    }

    @After fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    @Test fun `direct feed redirects return the final subscription URL`() = runBlocking {
        documents["/old-feed"] = Document(location = url("/feeds/canonical.xml"), code = 301)
        documents["/feeds/canonical.xml"] = rss()

        val result = helper.searchFeed(url("/old-feed", host = "localhost"))

        assertEquals(url("/feeds/canonical.xml"), result.feedLink)
        assertEquals("Fixture feed", result.feed.title)
        assertEquals(listOf("/old-feed", "/feeds/canonical.xml"), requests.toList())
    }

    @Test fun `relative discovery uses the redirected page host and directory`() = runBlocking {
        documents["/old-page"] = Document(location = url("/news/index.html"))
        documents["/news/index.html"] = html("rss.xml")
        documents["/news/rss.xml"] = rss()

        val result = helper.searchFeed(url("/old-page", host = "localhost"))

        assertEquals(url("/news/rss.xml"), result.feedLink)
        assertEquals(listOf("/old-page", "/news/index.html", "/news/rss.xml"), requests.toList())
    }

    @Test fun `discovered feeds retain their final URL after further redirects`() = runBlocking {
        documents["/page"] = html("/feed-alias")
        documents["/feed-alias"] = Document(location = url("/another-alias"), code = 307)
        documents["/another-alias"] = Document(location = url("/feeds/canonical.xml"), code = 308)
        documents["/feeds/canonical.xml"] = rss()

        val result = helper.searchFeed(url("/page"))

        assertEquals(url("/feeds/canonical.xml"), result.feedLink)
        assertEquals(listOf("/page", "/feed-alias", "/another-alias", "/feeds/canonical.xml"), requests.toList())
    }

    @Test fun `HTML base elements remain relative to the redirected page`() = runBlocking {
        documents["/old-page"] = Document(location = url("/news/index.html"))
        documents["/news/index.html"] = html("rss.xml", base = "../feeds/")
        documents["/feeds/rss.xml"] = rss()

        assertEquals(url("/feeds/rss.xml"), helper.searchFeed(url("/old-page")).feedLink)
    }

    @Test fun `direct feeds without redirects keep their URL and need one fetch`() = runBlocking {
        documents["/feed.xml"] = rss()

        assertEquals(url("/feed.xml"), helper.searchFeed(url("/feed.xml")).feedLink)
        assertEquals(listOf("/feed.xml"), requests.toList())
    }

    private fun url(path: String, host: String = "127.0.0.1") =
        server.url(path).newBuilder().host(host).build().toString()

    private fun html(href: String, base: String = "") = Document(
        body = """<html><head><base href="$base"><link rel="alternate" type="application/rss+xml" href="$href"></head><body>News</body></html>""",
    )

    private fun rss() = Document(
        body = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Fixture feed</title><link>https://example.com/</link><description>Feed discovery fixture</description></channel></rss>""",
        type = "application/rss+xml; charset=UTF-8",
    )
}
