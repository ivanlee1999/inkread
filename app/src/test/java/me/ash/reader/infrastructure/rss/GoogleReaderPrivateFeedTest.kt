package me.ash.reader.infrastructure.rss

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.infrastructure.exception.GoogleReaderAPIException
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import timber.log.Timber

/** Real loopback HTTP only; no account, subscription, or remote service is contacted. */
class GoogleReaderPrivateFeedTest {
    private lateinit var server: MockWebServer
    private lateinit var logger: SyncLogger
    private lateinit var api: GoogleReaderAPI
    private val logMessages = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logMessages += message + t?.stackTraceToString().orEmpty()
        }
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        logger = mock()
        api = GoogleReaderAPI.getInstance(mock<Context>(), server.url("/").toString(),
            "fixture-reader", "fixture-password", syncLogger = logger)
        api.clearAuthData()
        Timber.plant(tree)
    }

    @After fun tearDown() {
        Timber.uproot(tree)
        server.shutdown()
    }

    @Test fun privateQuickAddErrorsNeverRetainPrivateUrlsInDiagnostics() = runBlocking {
        enqueueFailingAttempts()

        val error = try {
            api.subscriptionQuickAdd(PRIVATE_URL, isPrivateFeed = true)
            error("Expected private feed failure")
        } catch (expected: GoogleReaderAPIException) { expected }

        assertPrivateFailure(error)
        val requests = (1..6).map { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        val quickAdd = requests.filter { it.path?.startsWith("/reader/api/0/subscription/quickadd") == true }
        assertEquals(2, quickAdd.size)
        for (request in quickAdd) {
            assertEquals(PRIVATE_URL, request.requestUrl!!.queryParameter("quickadd"))
            assertEquals(setOf("output", "quickadd"), request.requestUrl!!.queryParameterNames)
            assertTrue(request.body.readUtf8().contains("private-token"))
        }
    }

    @Test fun privateFeedEditRedactsTokenizedStreamIdAndEchoedBody() = runBlocking {
        enqueueFailingAttempts()

        val error = try {
            api.subscriptionEdit(action = "unsubscribe", destFeedId = PRIVATE_URL,
                destFeedName = "Newsletter private-token", isPrivateFeed = true)
            error("Expected private feed edit failure")
        } catch (expected: GoogleReaderAPIException) { expected }

        assertPrivateFailure(error)
    }

    @Test fun privateSuccessfulResponseStillReturnsTheServerIdentity() = runBlocking {
        enqueueAuthentication()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"numResults":1,"streamId":"feed/private-token","streamName":"Newsletter"}"""))

        val result = api.subscriptionQuickAdd(PRIVATE_URL, isPrivateFeed = true)

        assertEquals("feed/private-token", result.streamId)
        assertTrue(logMessages.isEmpty())
    }

    @Test fun ordinaryRequestsCannotLeakPrivateUrlsEchoedByTheServerEither() = runBlocking {
        enqueueFailingAttempts()

        val failure = try {
            api.subscriptionQuickAdd("https://public.example/feed")
            error("Expected server failure")
        } catch (expected: GoogleReaderAPIException) { expected }

        assertPrivateFailure(failure)
        assertEquals("Google Reader request failed (HTTP 500)", failure.message)
    }

    @Test fun boundedRefreshFailureDoesNotLogReturnedArticleContent() = runBlocking {
        enqueueFailingAttempts()

        val failure = try {
            api.getItemsContents(listOf("123"), maxResponseBytes = 4096)
            error("Expected refresh failure")
        } catch (expected: GoogleReaderAPIException) { expected }

        assertPrivateFailure(failure)
    }

    @Test fun subscriptionListFailureHasSafeRetryDiagnostics() = runBlocking {
        enqueueFailingAttempts()

        assertNull(api.getSubscriptionList())

        assertTrue(logMessages.isNotEmpty())
        assertTrue(logMessages.all { !it.contains("private-token") && !it.contains("Could not access") })
    }

    @Test fun feedStreamGetQueryPreservesEntireSignedUrl() = runBlocking {
        enqueueAuthentication()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"itemRefs":[]}"""))

        assertNotNull(api.getItemIdsForFeed(PRIVATE_URL))

        val request = (1..3).map { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }.last()
        assertEquals("feed/$PRIVATE_URL", request.requestUrl!!.queryParameter("s"))
        assertEquals(setOf("output", "s", "n"), request.requestUrl!!.queryParameterNames)
    }

    @Test fun directCredentialRefreshRedactsEchoedAuthenticationFailures() = runBlocking {
        for (status in listOf(500, 200)) {
            api.clearAuthData()
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody("Could not access $PRIVATE_URL with fixture-password"))

            val failure = try {
                api.refreshCredentialsIfNeeded()
                error("Expected authentication failure")
            } catch (expected: GoogleReaderAPIException) { expected }

            assertEquals("authentication", failure.query)
            assertNull(failure.cause)
            assertNull(failure.params)
            assertNull(failure.form)
            val diagnostic = failure.stackTraceToString()
            assertFalse(diagnostic.contains("private-token"))
            assertFalse(diagnostic.contains("private.example"))
            assertFalse(diagnostic.contains("Could not access"))
            assertFalse(diagnostic.contains("fixture-password"))
            assertEquals("/accounts/ClientLogin", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        }
    }

    private fun enqueueAuthentication() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"Auth":"fixture-login-token"}"""))
        server.enqueue(MockResponse().setBody("fixture-action-token"))
    }

    private fun enqueueFailingAttempts() = repeat(2) {
        enqueueAuthentication()
        server.enqueue(MockResponse().setResponseCode(500).setBody("Could not access $PRIVATE_URL"))
    }

    private suspend fun assertPrivateFailure(error: GoogleReaderAPIException) {
        val logged = argumentCaptor<Throwable>()
        verify(logger).log(logged.capture())
        val diagnostics = listOf(error.stackTraceToString(), logged.firstValue.stackTraceToString()) + logMessages
        assertTrue("The test must exercise retry diagnostics", logMessages.isNotEmpty())
        for (diagnostic in diagnostics) {
            assertFalse(diagnostic, diagnostic.contains("private-token"))
            assertFalse(diagnostic, diagnostic.contains("private.example"))
            assertFalse(diagnostic, diagnostic.contains("Could not access"))
            assertFalse(diagnostic, diagnostic.contains("fixture-password"))
        }
        assertNull(error.cause)
        assertNull(error.params)
        assertNull(error.form)
    }

    private companion object {
        const val PRIVATE_URL = "https://private.example/newsletter/private-token?secret=a%2Fb&key=private-token#section"
    }
}
