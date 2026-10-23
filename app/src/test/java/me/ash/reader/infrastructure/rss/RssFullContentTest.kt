package me.ash.reader.infrastructure.rss

import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/** Synthetic fixtures exercise actual HTTP decoding, redirects, and Readability together. */
class RssFullContentTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var helper: RssHelper
    private val paragraph = "中文报道与 English reporting remain intact. " +
        "The garden has many trees and winding paths, and local visitors describe their history. " +
        "This synthetic article records seasonal changes, planting choices, and community projects."

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient()
        helper = RssHelper(mock(), Dispatchers.IO, client)
    }

    @After fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    @Test fun redirectedArticleResolvesRelativeLinksAndImagesAgainstFinalUrl() = runBlocking {
        val finalUrl = server.url("/news/2026/story.html").newBuilder().host("localhost").build()
        val originalUrl = server.url("/old/story").newBuilder().host("127.0.0.1").build()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", finalUrl))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8").setBody(article()))

        val result = Jsoup.parseBodyFragment(helper.parseFullContent(originalUrl.toString(), "Fixture headline"))

        assertEquals(finalUrl.resolve("images/garden.jpg").toString(), result.selectFirst("img")!!.attr("src"))
        assertEquals(finalUrl.resolve("../related.html").toString(), result.selectFirst("a")!!.attr("href"))
        assertTrue(result.text().contains(paragraph))
        assertEquals("/old/story", server.takeRequest().path)
        assertEquals("/news/2026/story.html", server.takeRequest().path)
    }

    @Test fun metaEncodedChineseArticleRemainsReadableAfterExtraction() = runBlocking {
        val html = article("<meta charset='GB18030'>")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
            .setBody(Buffer().write(html.toByteArray(Charset.forName("GB18030")))))

        val result = Jsoup.parseBodyFragment(helper.parseFullContent(server.url("/story").toString(), "Fixture headline"))

        assertTrue(result.text().contains(paragraph))
        assertFalse(result.text().contains('\ufffd'))
    }

    @Test fun malformedHttpCharsetFallsBackInsteadOfLosingArticle() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=invalid-charset").setBody(article()))

        val result = Jsoup.parseBodyFragment(helper.parseFullContent(server.url("/story").toString(), "Fixture headline"))

        assertTrue(result.text().contains(paragraph))
    }

    private fun article(meta: String = "") = "<html><head><title>Fixture headline</title>$meta</head><body>" +
        "<article><h1>Fixture headline</h1>" + (1..5).joinToString("") { "<p>$paragraph</p>" } +
        "<p><a href='../related.html'>More about the garden</a></p>" +
        "<img src='images/garden.jpg' alt='Garden'></article></body></html>"
}
