package me.ash.reader.infrastructure.rss

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Pure URL screening plus DNS screening at connection time, including every redirect. */
internal fun publicArticleUrl(value: String?): HttpUrl? {
    if (value.isNullOrBlank() || value.length > 8192 || value.any { it <= '\u001f' || it == '\u007f' }) return null
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443)) return null
    val host = url.host.lowercase().trimEnd('.')
    if (host == "localhost" || !host.contains('.') && !host.contains(':') ||
        listOf(".localhost", ".local", ".internal", ".lan", ".home", ".onion").any(host::endsWith)) return null
    if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        if (!isPublicArticleAddress(address)) return null
    }
    return url
}

internal fun isPublicArticleAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            a == 100 && b in 64..127 || a == 169 && b == 254 || a == 172 && b in 16..31 ||
            a == 192 && (b == 168 || b == 0 || b == 2) ||
            a == 198 && (b in 18..19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113)
    }
    // Only ordinary global-unicast IPv6; exclude unique-local, transition/tunnel and docs ranges.
    return bytes.size == 16 && bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && bytes[1] == 0x01 && (bytes[2] == 0 || bytes[2] == 0x0d && bytes[3] == 0xb8)) &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02)
}

internal class PublicArticleDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicArticleAddress(it) }) {
            throw UnknownHostException("Article image host is not public")
        }
        return addresses
    }
}

/** Not the app's legacy trust-all client: no cookies, credentials, proxies, or insecure TLS. */
internal fun articleImageHttpClient(maxBytes: Long = 12L * 1024L * 1024L, rejectOversizeLength: Boolean = true): OkHttpClient =
    OkHttpClient.Builder()
        .dns(PublicArticleDns())
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(Interceptor { chain ->
            var request = chain.request().newBuilder()
                .removeHeader("Authorization").removeHeader("Proxy-Authorization")
                .removeHeader("Cookie").removeHeader("Referer").build()
            var redirects = 0
            while (true) {
                if (publicArticleUrl(request.url.toString()) == null) throw IOException("Article image URL is not public")
                val response = chain.proceed(request)
                if (response.code !in listOf(301, 302, 303, 307, 308)) {
                    val body = response.body
                    if (rejectOversizeLength && body.contentLength() > maxBytes) {
                        response.close()
                        throw IOException("Article image response exceeds limit")
                    }
                    return@Interceptor response.newBuilder().body(object : ResponseBody() {
                        private val boundedSource = object : ForwardingSource(body.source()) {
                            private var readBytes = 0L
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                // Prefix mode must stop at the cap before Okio's eager buffer fill.
                                if (!rejectOversizeLength && readBytes >= maxBytes) return -1L
                                val remaining = maxBytes + (if (rejectOversizeLength) 1 else 0) - readBytes
                                val read = super.read(sink, minOf(byteCount, remaining))
                                if (read > 0) readBytes += read
                                if (readBytes > maxBytes) throw IOException("Article image response exceeds limit")
                                return read
                            }
                        }.buffer()
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source(): BufferedSource = boundedSource
                    }).build()
                }
                val location = response.header("Location")?.let { request.url.resolve(it) }
                response.close()
                if (++redirects > 3 || location == null || publicArticleUrl(location.toString()) == null ||
                    request.url.isHttps && !location.isHttps) throw IOException("Article image redirect not allowed")
                request = request.newBuilder().url(location).build()
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Article image request failed")
        })
        .build()
