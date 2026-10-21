package me.ash.reader.infrastructure.rss

import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ArticleImageNetworkTest {
    @Test fun acceptsPublicHttpAndHttpsWithoutCredentials() {
        listOf("https://example.com/photo.jpg", "http://example.com/story?token=abc", "https://8.8.8.8/image", "https://[2606:4700:4700::1111]/image")
            .forEach { assertNotNull(it, publicArticleUrl(it)) }
    }

    @Test fun rejectsLocalPrivateReservedCredentialAndNonHttpDestinations() {
        listOf("http://localhost/a", "http://router/a", "http://foo.local/a", "http://foo.internal/a",
            "http://foo.localhost/a", "http://127.0.0.1/a", "http://127.1/a", "http://2130706433/a",
            "http://10.0.0.1/a", "http://192.168.2.1/a", "http://172.16.2.1/a", "http://169.254.169.254/a",
            "http://100.100.100.200/a", "http://0.0.0.0/a", "http://198.18.0.1/a", "http://192.0.2.1/a",
            "http://[::1]/a", "http://[fc00::1]/a", "http://[fe80::1]/a", "http://[::ffff:127.0.0.1]/a",
            "https://[2001:db8::1]/a", "http://224.0.0.1/a", "https://example.com:8443/a",
            "https://u:p@example.com/a", "file:///private", "data:image/png,abc", "https://example.com/\na")
            .forEach { assertNull(it, publicArticleUrl(it)) }
    }

    @Test fun dnsRejectsMixedPublicAndPrivateAnswers() {
        val dns = PublicArticleDns(Dns { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1")) })
        try { dns.lookup("public-looking.example.com"); fail("Mixed DNS answers must fail closed") }
        catch (_: UnknownHostException) { }
    }

    @Test fun dnsPermitsPublicAnswers() {
        val public = InetAddress.getByName("8.8.8.8")
        assertEquals(listOf(public), PublicArticleDns(Dns { listOf(public) }).lookup("example.com"))
    }

    @Test fun independentClientHasStrictTimeoutRedirectCookieAndProxyPolicy() {
        val client = articleImageHttpClient()
        assertEquals(8000, client.callTimeoutMillis)
        assertEquals(4000, client.connectTimeoutMillis)
        assertEquals(5000, client.readTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.cookieJar.loadForRequest(publicArticleUrl("https://example.com")!!).isEmpty())
    }
}

/** Interceptor tests use synthetic responses; no internet or local server access. */
class ArticleImageInterceptorTest {
    private fun chain(
        url: String = "https://example.com/article",
        reply: (okhttp3.Request, Int) -> okhttp3.Response,
    ) = FakeArticleChain(okhttp3.Request.Builder().url(url)
        .header("Authorization", "do-not-forward").header("Cookie", "do-not-forward")
        .header("Referer", "https://private.example.com/?token=secret").build(), reply)

    private fun response(request: okhttp3.Request, text: String = "hello", code: Int = 200, location: String? = null): okhttp3.Response =
        okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("fixture")
            .apply { if (location != null) header("Location", location) }
            .body(text.toResponseBody()).build()

    @Test fun stripsSensitiveHeadersBeforeEveryPublicRedirect() {
        val chain = chain { request, count ->
            assertNull(request.header("Authorization")); assertNull(request.header("Cookie")); assertNull(request.header("Referer"))
            if (count == 1) response(request, code = 302, location = "https://cdn.example.com/photo") else response(request)
        }
        articleImageHttpClient().interceptors.single().intercept(chain).use { assertEquals("hello", it.body.string()) }
        assertEquals(2, chain.requests)
    }

    @Test fun rejectsPrivateRedirectAndHttpsDowngradeBeforeConnecting() {
        listOf("http://127.0.0.1/private", "https://router.local/private", "http://example.com/plain").forEach { location ->
            val chain = chain { request, _ -> response(request, code = 302, location = location) }
            try { articleImageHttpClient().interceptors.single().intercept(chain); fail(location) } catch (_: java.io.IOException) { }
            assertEquals(1, chain.requests)
        }
    }

    @Test fun redirectsHaveAHardCap() {
        val chain = chain { request, count -> response(request, code = 302, location = "https://example.com/$count") }
        try { articleImageHttpClient().interceptors.single().intercept(chain); fail("redirect loop") } catch (_: java.io.IOException) { }
        assertEquals(4, chain.requests)
    }

    @Test fun strictImagesRejectOversizeContentLength() {
        val chain = chain { request, _ -> response(request, "x".repeat(32)) }
        try { articleImageHttpClient(maxBytes = 16).interceptors.single().intercept(chain); fail("oversize") } catch (_: java.io.IOException) { }
    }

    @Test fun metadataCanReadBoundedPrefixOfLongContentLengthPage() {
        val chain = chain { request, _ -> response(request, "x".repeat(32)) }
        articleImageHttpClient(maxBytes = 16, rejectOversizeLength = false).interceptors.single().intercept(chain).use {
            assertEquals("x".repeat(16), it.body.source().readUtf8(16))
        }
    }

    @Test fun streamingBodyCannotReadBeyondBudget() {
        val chain = chain { request, _ -> response(request).newBuilder().body(object : okhttp3.ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
            override fun source(): okio.BufferedSource = okio.Buffer().writeUtf8("x".repeat(32))
        }).build() }
        articleImageHttpClient(maxBytes = 16).interceptors.single().intercept(chain).use {
            try { it.body.string(); fail("stream must stop at limit") } catch (_: java.io.IOException) { }
        }
    }
}

private class FakeArticleChain(
    private val initial: okhttp3.Request,
    private val reply: (okhttp3.Request, Int) -> okhttp3.Response,
) : okhttp3.Interceptor.Chain {
    var requests = 0
    override fun request() = initial
    override fun proceed(request: okhttp3.Request): okhttp3.Response = reply(request, ++requests)
    override fun connection(): okhttp3.Connection? = null
    override fun call(): okhttp3.Call = okhttp3.OkHttpClient().newCall(initial)
    override fun connectTimeoutMillis() = 4000
    override fun readTimeoutMillis() = 5000
    override fun writeTimeoutMillis() = 5000
    override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
    override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
    override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
}
