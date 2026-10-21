package me.ash.reader.infrastructure.rss

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okio.Buffer

internal enum class ArticleImageOutcome { FOUND, NO_IMAGE, FAILURE }
internal data class ArticleImageResult(val candidates: List<String>, val outcome: ArticleImageOutcome)

/** Small disposable on-device cache, not article data. Failures expire sooner than empty pages. */
internal class ArticleImageMetadataCache(
    private val capacity: Int = 256,
    private val now: () -> Long = System::currentTimeMillis,
    private val file: File? = null,
) {
    private data class Entry(val result: ArticleImageResult, val expiresAt: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var restored = false

    @Synchronized fun get(url: String): ArticleImageResult? {
        restore()
        val key = cacheKey(url)
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= now()) { entries.remove(key); return null }
        return entry.result
    }

    @Synchronized fun put(url: String, result: ArticleImageResult) {
        restore()
        val ttl = when (result.outcome) {
            ArticleImageOutcome.FOUND -> TimeUnit.HOURS.toMillis(24)
            ArticleImageOutcome.NO_IMAGE -> TimeUnit.HOURS.toMillis(6)
            ArticleImageOutcome.FAILURE -> TimeUnit.MINUTES.toMillis(15)
        }
        entries[cacheKey(url)] = Entry(result.copy(candidates = result.candidates.filter { publicArticleUrl(it) != null }.distinct().take(ARTICLE_IMAGE_MAX_CANDIDATES)), now() + ttl)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        persist()
    }

    private fun restore() {
        if (restored) return
        restored = true
        val source = file ?: return
        // Malformed/oversized/old cache files are disposable. Never fetch from an unvalidated entry.
        if (!source.isFile || source.length() > 1024 * 1024) return
        runCatching {
            val rows = Json.parseToJsonElement(source.readText()) as? JsonArray ?: return@runCatching
            rows.takeLast(capacity).forEach { value ->
                val row = value as? JsonObject ?: return@forEach
                val key = (row["key"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val expires = (row["expires"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                val outcome = (row["outcome"] as? JsonPrimitive)?.contentOrNull
                    ?.let { runCatching { ArticleImageOutcome.valueOf(it) }.getOrNull() } ?: return@forEach
                if (!key.matches(Regex("[0-9a-f]{64}")) || expires <= now() || expires > now() + TimeUnit.HOURS.toMillis(24)) return@forEach
                val images = (row["images"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.takeIf { image -> publicArticleUrl(image) != null }
                }.distinct().take(ARTICLE_IMAGE_MAX_CANDIDATES)
                entries[key] = Entry(ArticleImageResult(images, outcome), expires)
            }
        }
    }

    private fun cacheKey(url: String): String = articleImageCacheKey(url)

    private fun persist() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            // Budget before JSON construction/writing, including worst-case escaping/UTF-8.
            fun estimatedBytes() = entries.values.sumOf { 512L + it.result.candidates.sumOf { image -> image.length.toLong() * 6L } }
            while (entries.isNotEmpty() && estimatedBytes() > 900_000L) entries.remove(entries.keys.first())
            val rows = entries.filterValues { it.expiresAt > now() }.map { (url, entry) ->
                JsonObject(mapOf(
                    "key" to JsonPrimitive(url),
                    "expires" to JsonPrimitive(entry.expiresAt),
                    "outcome" to JsonPrimitive(entry.result.outcome.name),
                    "images" to JsonArray(entry.result.candidates.map(::JsonPrimitive)),
                ))
            }
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(JsonArray(rows).toString())
            if (temporary.length() <= 1024 * 1024) temporary.renameTo(target) else temporary.delete()
        }
    }
}

/** Keep persisted keys identical without allocating a Formatter for every digest byte. */
internal fun articleImageCacheKey(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    val chars = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = hex[value ushr 4]
        chars[index * 2 + 1] = hex[value and 0x0f]
    }
    return String(chars)
}

/** Striped locks deduplicate a URL, without an unbounded map or app-scope background crawl. */
internal class ArticleImageEnricher(
    private val cache: ArticleImageMetadataCache = ArticleImageMetadataCache(),
    private val fetch: suspend (String) -> List<String> = ::fetchArticleImageMetadata,
) {
    private val permits = Semaphore(2)
    private val locks = Array(32) { Mutex() }

    suspend fun candidates(articleUrl: String, allowNetwork: Boolean = true): List<String> = withContext(Dispatchers.IO) {
        val url = publicArticleUrl(articleUrl)?.toString() ?: return@withContext emptyList()
        locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cache.get(url)?.let { return@withLock it.candidates }
            if (!allowNetwork) return@withLock emptyList()
            permits.withPermit {
                currentCoroutineContext().ensureActive()
                val result = try {
                    val fetched = withTimeoutOrNull(10_000) { fetch(url) }
                    val images = fetched.orEmpty().filter { publicArticleUrl(it) != null }
                        .distinct().take(ARTICLE_IMAGE_MAX_CANDIDATES)
                    ArticleImageResult(images, if (fetched == null) ArticleImageOutcome.FAILURE else if (images.isEmpty()) ArticleImageOutcome.NO_IMAGE else ArticleImageOutcome.FOUND)
                } catch (cancelled: CancellationException) {
                    // Images-off/disposal cancels requests, and must not cache a false negative.
                    throw cancelled
                } catch (failure: Exception) {
                    if (isTransientArticleImageFailure(failure)) return@withPermit emptyList()
                    ArticleImageResult(emptyList(), ArticleImageOutcome.FAILURE)
                }
                currentCoroutineContext().ensureActive()
                cache.put(url, result)
                result.candidates
            }
        }
    }
}

internal object ArticleImageEnrichment {
    // Separate from full-article reading: no authentication, cookies, extraction services or JS.
    val imageClient by lazy { articleImageHttpClient() }
    private var enricher: ArticleImageEnricher? = null
    private val failed = LinkedHashMap<String, Long>()
    private val successful = ArticleImageMetadataCache()

    private fun requestKey(request: ArticleImageRequest) = request.articleUrl ?: request.candidates.joinToString("\n")
    fun preferredCandidates(request: ArticleImageRequest?): List<String> =
        request?.let { successful.get(requestKey(it))?.candidates }.orEmpty()
    fun recordSuccess(request: ArticleImageRequest, url: String) {
        successful.put(requestKey(request), ArticleImageResult(listOf(url), ArticleImageOutcome.FOUND))
    }

    @Synchronized fun forCacheDirectory(directory: File): ArticleImageEnricher = enricher ?: ArticleImageEnricher(
        ArticleImageMetadataCache(file = File(directory, "article-image-metadata-v1.json")),
    ).also { enricher = it }

    @Synchronized fun failedRecently(url: String): Boolean {
        val expiry = failed[url] ?: return false
        if (expiry <= System.currentTimeMillis()) { failed.remove(url); return false }
        return true
    }

    @Synchronized fun recordFailure(url: String) {
        failed[url] = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
        while (failed.size > 512) failed.remove(failed.keys.first())
    }
}

private val metadataClient by lazy { articleImageHttpClient(ARTICLE_IMAGE_MAX_HTML_BYTES, rejectOversizeLength = false) }

internal fun isTransientArticleImageFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).any {
        it is UnknownHostException || it is ConnectException || it is SocketException || it is InterruptedIOException
    }

private suspend fun fetchArticleImageMetadata(url: String): List<String> = suspendCancellableCoroutine { continuation ->
    val call = metadataClient.newCall(Request.Builder().url(url)
        .header("Accept", "text/html,application/xhtml+xml")
        .header("User-Agent", "InkRead-ArticleImages/1.0").build())
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val images = response.use {
                    if (!it.isSuccessful) throw IOException("Article image metadata unavailable")
                    val contentType = it.body.contentType()
                    if (contentType != null && contentType.subtype !in setOf("html", "xhtml+xml")) {
                        throw IOException("Article image metadata is not HTML")
                    }
                    val buffer = Buffer()
                    val source = it.body.source()
                    while (buffer.size < ARTICLE_IMAGE_MAX_HTML_BYTES && continuation.isActive) {
                        if (source.read(buffer, minOf(8192, ARTICLE_IMAGE_MAX_HTML_BYTES - buffer.size)) == -1L) break
                    }
                    if (!continuation.isActive) return
                    val html = buffer.readString(contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                    rankedArticleImageCandidates(html, it.request.url.toString())
                }
                if (continuation.isActive) continuation.resume(images)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { call.cancel() }
        }
    })
}
