package me.ash.reader.infrastructure.report

import android.content.Context
import java.net.SocketTimeoutException
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.html.ArticleExtractionPreparation
import me.ash.reader.infrastructure.html.ArticleRequiresBrowserException
import me.ash.reader.infrastructure.html.ModernReadability
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.ui.page.adaptive.selectReaderContent
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class ExtractionAttemptCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://example.org/article"
    private val body = (1..23).joinToString("") { "<p>Editorial paragraph $it. ${"Visible prose stays in the article. ".repeat(6)}</p>" }
    private val page = "<html><head><title>Report fixture</title></head><body><article>$body</article></body></html>"
    private fun rss(status: Int = 200, content: String = page, mediaType: String = "text/html") = RssHelper(
        mock<Context>(), Dispatchers.IO, OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(status).message("Synthetic status")
                .protocol(Protocol.HTTP_1_1).body(content.toResponseBody(mediaType.toMediaType())).build()
        }.build(),
    )
    private fun report(trace: ExtractionAttemptSnapshot?) = ExtractionProblemSnapshot(
        capturedAtEpochMillis = 1, appVersion = "0.16.24", appVersionCode = 69, deviceModel = "Fixture", androidApi = 30,
        publicArticleUrl = url, requestedSourceMode = ExtractionSourceMode.LOCAL,
        displayedSourceMode = ExtractionSourceMode.SERVER, lastLocalAttempt = trace,
    )

    @Test fun `no observed local operation stays absent instead of becoming an error`() {
        val capture = ExtractionAttemptCapture(false)
        capture.finish()
        assertNull(capture.snapshot())
        assertTrue(ExtractionProblemFormatter.markdown(report(null), "").contains("No local extraction attempt recorded"))
    }

    @Test fun `successful twenty three paragraph parse with lost enclosure records image veto not parser or paywall failure`() = runBlocking<Unit> {
        val capture = ExtractionAttemptCapture(true)
        val feed = "<p>Short feed summary.</p><figure class='enclosure'><p class='enclosure-content'>" +
            "<img class='enclosure-thumbnail' src='https://reader.example.org/article-img-proxy.php?url=photo.jpg'></p></figure>"
        runBlocking(capture) {
            val candidate = rss().parseFullContent(url, "Report fixture")
            selectReaderContent(feed, candidate, diagnostic = capture)
        }
        capture.finish()
        val trace = capture.snapshot()!!
        assertEquals(ExtractionAttemptOrigin.FRESH, trace.origin)
        assertEquals(200, trace.httpStatus)
        assertEquals(page.toByteArray().size.toLong(), trace.responseBytesRead)
        assertEquals(true, trace.responseBytesComplete)
        assertNotNull(trace.parserElapsedMillis)
        assertEquals(23, trace.fetchedCandidate!!.paragraphs)
        assertEquals(0, trace.fetchedCandidate.images)
        assertEquals(1, trace.readerComparison!!.before!!.images)
        assertEquals(ExtractionQualityVeto.IMAGE_LOSS, trace.readerComparison.detail)
        assertEquals(ExtractionFailureStage.QUALITY, trace.failureStage)
        assertEquals(ExtractionDiagnosticReason.QUALITY_REJECTED, trace.reason)
        assertEquals(ExtractionAttemptOutcome.FEED_RETAINED, trace.outcome)
        val json = ExtractionProblemFormatter.json(report(trace), "")
        assertTrue(json.contains("image_loss"))
        assertFalse(json.contains("Editorial paragraph"))
        assertFalse(json.contains("article-img-proxy"))
        assertFalse(json.contains("publisher_restricted"))
        assertFalse(json.contains("engine_error"))
    }

    @Test fun `HTTP access response records known status but no invented body size or parser attempt`() = runBlocking<Unit> {
        val capture = ExtractionAttemptCapture(false)
        runBlocking(capture) { assertTrue(runCatching { rss(403).parseFullContent(url, "Report fixture") }.isFailure) }
        capture.finish()
        val trace = capture.snapshot()!!
        assertEquals(403, trace.httpStatus)
        assertEquals(ExtractionFailureStage.ACCESS, trace.failureStage)
        assertEquals(ExtractionDiagnosticReason.ACCESS_GATE, trace.reason)
        assertNull(trace.responseBytesRead)
        assertNull(trace.parserElapsedMillis)
        assertNull(trace.fetchedCandidate)
    }

    @Test fun `non HTML and bounded response errors remain distinct without reading or exporting their bodies`() = runBlocking<Unit> {
        for ((content, type, expected) in listOf(
            Triple("{\"do_not_export\":\"private\"}", "application/json", ExtractionDiagnosticReason.NON_HTML_RESPONSE),
            Triple("x".repeat(2 * 1024 * 1024 + 1), "text/html", ExtractionDiagnosticReason.INPUT_LIMIT),
        )) {
            val capture = ExtractionAttemptCapture(true)
            runBlocking(capture) { assertTrue(runCatching { rss(content = content, mediaType = type).parseFullContent(url, "Title") }.isFailure) }
            capture.finish()
            val trace = capture.snapshot()!!
            assertEquals(200, trace.httpStatus)
            assertEquals(expected, trace.reason)
            assertEquals(0L, trace.responseBytesRead)
            assertEquals(false, trace.responseBytesComplete)
            assertNull(trace.parserElapsedMillis)
            assertFalse(ExtractionProblemFormatter.json(report(trace), "").contains("do_not_export"))
        }
    }

    @Test fun `network timeout never invents HTTP status and excludes raw exception message`() = runBlocking<Unit> {
        val capture = ExtractionAttemptCapture(false)
        val helper = RssHelper(mock(), Dispatchers.IO, OkHttpClient.Builder().addInterceptor {
            throw SocketTimeoutException("https://private.example.invalid/feed?token=never-export")
        }.build())
        runBlocking(capture) { assertTrue(runCatching { helper.parseFullContent(url, "Title") }.isFailure) }
        capture.finish()
        val trace = capture.snapshot()!!
        assertEquals(ExtractionDiagnosticReason.NETWORK_TIMEOUT, trace.reason)
        assertEquals(ExtractionFailureStage.FETCH, trace.failureStage)
        assertNull(trace.httpStatus)
        assertNull(trace.responseBytesRead)
        val json = ExtractionProblemFormatter.json(report(trace), "")
        assertFalse(json.contains("private.example"))
        assertFalse(json.contains("never-export"))
    }

    @Test fun `resource and publisher restriction reasons use typed facts only`() {
        val limited = ExtractionAttemptCapture(false)
        limited.failed(ModernReadability.ExtractionException(ModernReadability.Failure.TIME_LIMIT))
        limited.finish()
        assertEquals(ExtractionFailureStage.RESOURCE, limited.snapshot()!!.failureStage)
        assertEquals(ExtractionDiagnosticReason.TIME_LIMIT, limited.snapshot()!!.reason)
        assertNull(limited.snapshot()!!.httpStatus)
        val sourceLimited = ExtractionAttemptCapture(false)
        sourceLimited.failed(ArticleRequiresBrowserException(ArticleExtractionPreparation.AccessReason.SOURCE_LIMIT))
        sourceLimited.finish()
        assertEquals(ExtractionDiagnosticReason.SOURCE_LIMIT, sourceLimited.snapshot()!!.reason)
    }

    @Test fun `reader cache hit does not claim a fresh parse or HTTP response`() = runBlocking<Unit> {
        val article = Article("article", Date(0), "Title", rawDescription = "Summary", shortDescription = "Summary",
            link = url, feedId = "feed", accountId = 1)
        val parser: RssHelper = mock()
        whenever(parser.parseFullContent(any(), any())).thenReturn(body)
        val cache = ReaderCacheHelper(mock<Context> {
            on { cacheDir } doReturn temporary.root; on { filesDir } doReturn temporary.root
        }, Dispatchers.IO, parser, mock<AccountService>())
        cache.readOrFetchFullContent(article).getOrThrow()
        val capture = ExtractionAttemptCapture(false)
        runBlocking(capture) {
            val cached = cache.readOrFetchFullContent(article) { true }.getOrThrow()
            selectReaderContent(article.rawDescription, cached, diagnostic = capture)
        }
        capture.finish()
        val trace = capture.snapshot()!!
        assertEquals(ExtractionAttemptOrigin.CACHE, trace.origin)
        assertEquals(ExtractionCacheState.HIT, trace.cacheState)
        assertEquals(ExtractionDiagnosticReason.CACHE_REUSED, trace.reason)
        assertNull(trace.httpStatus)
        assertNull(trace.parserElapsedMillis)
        verify(parser, times(1)).parseFullContent(any(), any())
    }

    @Test fun `concurrent request contexts keep response and failure facts separate`() = runBlocking<Unit> {
        val helper = RssHelper(mock(), Dispatchers.IO, OkHttpClient.Builder().addInterceptor { chain ->
            val status = if (chain.request().url.encodedPath == "/a") 200 else 503
            Response.Builder().request(chain.request()).code(status).message("Synthetic")
                .protocol(Protocol.HTTP_1_1).body(page.toResponseBody("text/html".toMediaType())).build()
        }.build())
        val first = ExtractionAttemptCapture(false)
        val second = ExtractionAttemptCapture(true)
        val a = async(first) { runCatching { helper.parseFullContent("https://example.org/a", "Report fixture") } }
        val b = async(second) { runCatching { helper.parseFullContent("https://example.org/b", "Report fixture") } }
        assertTrue(a.await().isSuccess); assertTrue(b.await().isFailure)
        first.finish(); second.finish()
        assertEquals(200, first.snapshot()!!.httpStatus)
        assertEquals(503, second.snapshot()!!.httpStatus)
        assertEquals(ExtractionDiagnosticReason.HTTP_ERROR, second.snapshot()!!.reason)
        assertNull(second.snapshot()!!.fetchedCandidate)
        assertEquals(23, first.snapshot()!!.fetchedCandidate!!.paragraphs)
    }
}
