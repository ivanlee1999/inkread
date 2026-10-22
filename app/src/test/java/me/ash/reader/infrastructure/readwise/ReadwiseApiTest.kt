package me.ash.reader.infrastructure.readwise

import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** All HTTP responses are synthetic. No socket, token, account, or Readwise call is used. */
class ReadwiseApiTest {
    private val payload = ReadwiseHighlightPayload("selected quote", "my note", "Article", "Writer", "https://example.test/article", 1_700_000_000_000)
    private fun api(reply: (Request) -> Response): Pair<ReadwiseApi, FakeReadwiseCalls> {
        val calls = FakeReadwiseCalls(reply)
        return ReadwiseApi(calls).also { it.activate(7) } to calls
    }

    @Test fun dedicatedClientNeverUsesSharedTrustAllCacheRedirectOrLoggingSettings() {
        val client = readwiseHttpClient()
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(45_000, client.callTimeoutMillis)
        assertTrue(client.cookieJar.loadForRequest(Request.Builder().url("https://readwise.io").build().url).isEmpty())
        assertEquals(okhttp3.OkHttpClient().hostnameVerifier, client.hostnameVerifier)
    }

    @Test fun authRequiresDocumented204AndUsesTokenHeader() {
        val (api, calls) = api { response(it, 204) }
        assertEquals(ReadwiseResult.Success(Unit), api.authenticate("synthetic-token"))
        assertEquals("https://readwise.io/api/v2/auth/", calls.requests.single().url.toString())
        assertEquals("GET", calls.requests.single().method)
        assertEquals("Token synthetic-token", calls.requests.single().header("Authorization"))
        assertTrue(api { response(it, 200, "{}") }.first.authenticate("fixture") is ReadwiseResult.Invalid)
    }

    @Test fun createSendsOneHighlightAndUsesReturnedModifiedIdNotBookId() {
        val (api, calls) = api { response(it, 200, """[{"id":11,"modified_highlights":[]},{"id":22,"modified_highlights":[909]}]""") }
        assertEquals(ReadwiseResult.Success("909"), api.create(7, "fixture", payload))
        val request = calls.requests.single()
        val root = JsonParser.parseString(request.bodyText()).asJsonObject
        val highlights = root.getAsJsonArray("highlights")
        assertEquals(1, highlights.size())
        val highlight = highlights.single().asJsonObject
        assertEquals("selected quote", highlight.get("text").asString)
        assertEquals("my note", highlight.get("note").asString)
        assertEquals("inkread", highlight.get("source_type").asString)
        assertEquals("articles", highlight.get("category").asString)
        assertFalse(highlight.has("highlight_url"))
    }

    @Test fun optionalOffsetIsOrderingOnlyAndNeverTransmitsAnchorContext() {
        val (api, calls) = api { response(it, 200, """[{"modified_highlights":[909]}]""") }
        api.create(7, "fixture", payload.copy(location = 42))
        val highlight = JsonParser.parseString(calls.requests.single().bodyText()).asJsonObject.getAsJsonArray("highlights").single().asJsonObject
        assertEquals(42, highlight.get("location").asInt)
        assertEquals("offset", highlight.get("location_type").asString)
        assertFalse(highlight.has("anchor"))
        assertFalse(highlight.has("context"))
        assertNotNull(payload.copy(location = -1).validationError())
    }

    @Test fun omittedAuthorDoesNotBindAnUnrelatedKnownAuthor() {
        val (api, _) = api { response(it, 200, exportBody()) }
        assertTrue(api.reconcileCreate(7, "fixture", payload.copy(author = null)) is ReadwiseResult.ReconciliationRequired)
    }

    @Test fun twoModifiedIdsNeverBindByResponsePosition() {
        val (api, _) = api { response(it, 200, """[{"modified_highlights":[2,1]}]""") }
        assertTrue(api.create(7, "fixture", payload) is ReadwiseResult.ReconciliationRequired)
    }

    @Test fun dedupResponseReconcilesExactContentReadOnly() {
        val (api, calls) = api { request -> if (request.method == "POST") response(request, 200, """[{"modified_highlights":[]}]""") else response(request, 200, exportBody()) }
        assertEquals(ReadwiseResult.Success("818"), api.create(7, "fixture", payload))
        assertEquals(listOf("POST", "GET"), calls.requests.map { it.method })
        assertEquals("/api/v2/export/", calls.requests.last().url.encodedPath)
    }

    @Test fun verifiedPreSendFailureCanRetryCreateWithoutReconciliation() {
        val (api, _) = api { request ->
            request.tag(ReadwiseDeliveryTrace::class.java)!!.listenerObserved = true
            throw IOException("synthetic DNS failure before headers")
        }
        assertEquals(ReadwiseResult.NotSentRetryable, api.create(7, "fixture", payload))
    }

    @Test fun requestHeadersStartedMakesFailureUncertain() {
        val (api, _) = api { request ->
            request.tag(ReadwiseDeliveryTrace::class.java)!!.apply { listenerObserved = true; requestStarted = true }
            throw IOException("synthetic lost acknowledgement")
        }
        assertEquals(ReadwiseResult.Retryable, api.create(7, "fixture", payload))
    }

    @Test fun lostAcknowledgementRecoversViaGetWithoutRepeatingPost() {
        val (api, calls) = api { request -> if (request.method == "POST") throw IOException("synthetic lost acknowledgement") else response(request, 200, exportBody()) }
        assertEquals(ReadwiseResult.Retryable, api.create(7, "fixture", payload))
        assertEquals(ReadwiseResult.Success("818"), api.reconcileCreate(7, "fixture", payload))
        assertEquals(listOf("POST", "GET"), calls.requests.map { it.method })
    }

    @Test fun duplicateOrMissingRemoteMatchesRequireReview() {
        val duplicate = exportBody().replace("\"id\":818", "\"id\":819")
        val combined = JsonParser.parseString(exportBody()).asJsonObject.apply {
            getAsJsonArray("results").add(JsonParser.parseString(duplicate).asJsonObject.getAsJsonArray("results").single())
        }.toString()
        for (body in listOf(combined, """{"results":[],"nextPageCursor":null}""", exportBody().replace("my note", "changed elsewhere"))) {
            val (api, calls) = api { response(it, 200, body) }
            assertTrue(api.reconcileCreate(7, "fixture", payload) is ReadwiseResult.ReconciliationRequired)
            assertTrue(calls.requests.all { it.method == "GET" })
        }
    }

    @Test fun postSuccessThenUnauthorizedOrInvalidReconciliationStaysUncertain() {
        val (unauthorized, _) = api { request -> if (request.method == "POST") response(request, 200, """[{"modified_highlights":[]}]""") else response(request, 401) }
        assertEquals(ReadwiseResult.ReconciliationUnauthorized, unauthorized.create(7, "fixture", payload))
        val (forbidden, _) = api { request -> if (request.method == "POST") response(request, 200, """[{"modified_highlights":[]}]""") else response(request, 403) }
        assertTrue(forbidden.create(7, "fixture", payload) is ReadwiseResult.ReconciliationRequired)
    }

    @Test fun dedupThenFirstPageThrottleRequiresReconciliationBeforeAnotherPost() {
        val (api, _) = api { request ->
            if (request.method == "POST") response(request, 200, """[{"modified_highlights":[]}]""")
            else response(request, 429).newBuilder().header("Retry-After", "90").build()
        }
        assertEquals(ReadwiseResult.ReconciliationDeferred(90_000), api.create(7, "fixture", payload))
    }

    @Test fun interruptedMultiPageScanStopsVisiblyInsteadOfRescanningForever() {
        val (api, calls) = api { request ->
            if (request.url.query == null) response(request, 200, """{"results":[],"nextPageCursor":"page2"}""")
            else response(request, 429).newBuilder().header("Retry-After", "60").build()
        }
        assertEquals(ReadwiseResult.ReconciliationThrottled(60_000), api.reconcileCreate(7, "fixture", payload))
        assertEquals(2, calls.requests.size)
    }

    @Test fun paginationCursorIsEncodedOnFixedReadwiseOrigin() {
        val (api, calls) = api { request -> if (request.url.query == null)
            response(request, 200, """{"results":[],"nextPageCursor":"https://evil.test/?q=x&token=y"}""")
            else response(request, 200, exportBody()) }
        assertEquals(ReadwiseResult.Success("818"), api.reconcileCreate(7, "fixture", payload))
        assertTrue(calls.requests.all { it.url.host == "readwise.io" })
        assertEquals("https://evil.test/?q=x&token=y", calls.requests.last().url.queryParameter("pageCursor"))
    }

    @Test fun updateUsesPatchAndCanClearNotes() {
        val (api, calls) = api { response(it, 200, "{}") }
        assertEquals(ReadwiseResult.Success("123"), api.update(7, "fixture", "123", payload.copy(note = "")))
        assertEquals("PATCH", calls.requests.single().method)
        assertEquals("/api/v2/highlights/123/", calls.requests.single().url.encodedPath)
        assertEquals("", JsonParser.parseString(calls.requests.single().bodyText()).asJsonObject.get("note").asString)
    }

    @Test fun documentedLimitsRejectWithoutSilentTruncationOrAnyRequest() {
        listOf(payload.copy(text = "x".repeat(8192)), payload.copy(note = "x".repeat(8192)),
            payload.copy(title = "x".repeat(512)), payload.copy(author = "x".repeat(1025)),
            payload.copy(sourceUrl = "x".repeat(2048))).forEach { tooLong ->
            val (api, calls) = api { error("Must not send") }
            assertTrue(api.create(7, "fixture", tooLong) is ReadwiseResult.Invalid)
            assertTrue(calls.requests.isEmpty())
        }
        assertNull(payload.copy(text = "😀".repeat(8191), note = "x".repeat(8191), title = "x".repeat(511), author = "x".repeat(1024), sourceUrl = "x".repeat(2047)).validationError())
    }

    @Test fun statusesAreSanitizedAnd429UsesRetryAfter() {
        assertEquals(ReadwiseResult.Unauthorized, api { response(it, 401) }.first.create(7, "fixture", payload))
        val invalid = api { response(it, 400, "sensitive-server-echo") }.first.create(7, "fixture", payload) as ReadwiseResult.Invalid
        assertTrue(invalid.message.contains("400")); assertFalse(invalid.message.contains("sensitive-server-echo"))
        assertEquals(ReadwiseResult.RateLimited(123_000), api { response(it, 429).newBuilder().header("Retry-After", "123").build() }.first.create(7, "fixture", payload))
        assertEquals(ReadwiseResult.Retryable, api { response(it, 503) }.first.create(7, "fixture", payload))
    }

    @Test fun retryAfterSupportsHttpDateAndNeverNegative() {
        val now = Instant.parse("2015-10-21T07:27:00Z").toEpochMilli()
        assertEquals(60_000, retryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", now))
        assertEquals(0, retryAfterMillis("0", now))
        assertEquals(60_000, retryAfterMillis("nonsense", now))
    }

    @Test fun disconnectFencesStaleEpochAndCancelsInFlight() {
        lateinit var api: ReadwiseApi
        val calls = FakeReadwiseCalls { api.activate(null); response(it, 200, """[{"modified_highlights":[1]}]""") }
        api = ReadwiseApi(calls).also { it.activate(7) }
        assertEquals(ReadwiseResult.Cancelled, api.create(7, "fixture", payload))
        api.activate(8)
        assertEquals(ReadwiseResult.Cancelled, api.create(7, "fixture", payload))
        assertEquals(1, calls.requests.size)
    }

    @Test fun redirectIsNotFollowedAndUnsafeTokenRejectedBeforeRequest() {
        val (api, calls) = api { response(it, 302).newBuilder().header("Location", "https://other.test/").build() }
        assertTrue(api.create(7, "fixture", payload) is ReadwiseResult.Invalid)
        assertEquals(1, calls.requests.size)
        assertTrue(api.authenticate("bad\ntoken") is ReadwiseResult.Invalid)
        assertTrue(api.authenticate("bad☃token") is ReadwiseResult.Invalid)
        assertEquals(1, calls.requests.size)
    }

    @Test fun credentialsCannotLeakThroughToString() {
        assertFalse(ReadwiseCredential("id", "synthetic-secret").toString().contains("synthetic-secret"))
    }

    private fun exportBody() = """{"nextPageCursor":null,"results":[{"title":"Article","author":"Writer","source_url":"https://example.test/article","is_deleted":false,"highlights":[{"id":818,"text":"selected quote","note":"my note","is_deleted":false}]}]}"""
}

internal fun response(request: Request, code: Int, body: String = "") = Response.Builder()
    .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
    .body(body.toResponseBody()).build()

internal fun Request.bodyText(): String = Buffer().also { body?.writeTo(it) }.readUtf8()

internal class FakeReadwiseCalls(private val reply: (Request) -> Response) : Call.Factory {
    val requests = mutableListOf<Request>()
    override fun newCall(request: Request): Call = object : Call {
        private var cancelled = false
        private var executed = false
        private val timeout = Timeout()
        override fun request() = request
        override fun execute(): Response { executed = true; synchronized(requests) { requests += request }; return reply(request) }
        override fun enqueue(responseCallback: Callback) = error("Synchronous test only")
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = timeout
        override fun clone() = newCall(request)
    }
}
