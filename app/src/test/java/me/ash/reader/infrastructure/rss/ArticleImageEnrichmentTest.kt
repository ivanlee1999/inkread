package me.ash.reader.infrastructure.rss

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArticleImageEnrichmentTest {
    private val url = "https://example.com/story?private_token=do-not-store"
    private val image = "https://example.com/photo.jpg"

    @Test fun positiveNoImageAndFailureEntriesHaveSeparateTtls() {
        for ((outcome, ttl) in listOf(ArticleImageOutcome.FOUND to TimeUnit.HOURS.toMillis(24),
            ArticleImageOutcome.NO_IMAGE to TimeUnit.HOURS.toMillis(6), ArticleImageOutcome.FAILURE to TimeUnit.MINUTES.toMillis(15))) {
            var time = 1L
            val cache = ArticleImageMetadataCache(now = { time })
            val result = ArticleImageResult(if (outcome == ArticleImageOutcome.FOUND) listOf(image) else emptyList(), outcome)
            cache.put(url, result)
            time += ttl - 1
            assertEquals(result, cache.get(url))
            time++
            assertNull(cache.get(url))
        }
    }

    @Test fun cacheIsLruBoundedAndCapsPersistedCandidates() {
        val cache = ArticleImageMetadataCache(capacity = 2)
        val result = ArticleImageResult((0..20).map { "https://example.com/$it.jpg" }, ArticleImageOutcome.FOUND)
        cache.put("https://example.com/one", result)
        cache.put("https://example.com/two", result)
        assertEquals(6, cache.get("https://example.com/one")!!.candidates.size)
        cache.put("https://example.com/three", result)
        assertNull(cache.get("https://example.com/two"))
        assertNotNull(cache.get("https://example.com/one"))
    }

    @Test fun diskCacheSurvivesRecreationWithoutStoringArticleQuery() {
        val directory = kotlin.io.path.createTempDirectory("article-image-cache").toFile()
        try {
            val file = File(directory, "images.json")
            val result = ArticleImageResult(listOf(image), ArticleImageOutcome.FOUND)
            ArticleImageMetadataCache(now = { 1000 }, file = file).put(url, result)
            assertEquals(result, ArticleImageMetadataCache(now = { 2000 }, file = file).get(url))
            assertFalse(file.readText().contains("private_token"))
            assertFalse(file.readText().contains("do-not-store"))
            assertNull(ArticleImageMetadataCache(now = { TimeUnit.DAYS.toMillis(2) }, file = file).get(url))
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptDiskCacheIsIgnored() {
        val file = File.createTempFile("article-image", ".json")
        try { file.writeText("{broken"); assertNull(ArticleImageMetadataCache(file = file).get(url)) }
        finally { file.delete() }
    }

    @Test fun successNoImageAndNetworkFailureAreCachedWithoutRepeatFetch() = runBlocking {
        for (mode in 0..2) {
            var fetches = 0
            val enricher = ArticleImageEnricher(fetch = { fetches++; if (mode == 2) throw IOException("offline"); if (mode == 0) listOf(image) else emptyList() })
            repeat(3) { assertEquals(if (mode == 0) listOf(image) else emptyList<String>(), enricher.candidates(url)) }
            assertEquals(1, fetches)
        }
    }

    @Test fun unsafeArticleNeverReachesFetcherAndUnsafeImagesAreDiscarded() = runBlocking {
        var fetches = 0
        val enricher = ArticleImageEnricher(fetch = { fetches++; listOf("http://127.0.0.1/a", image, image) })
        assertTrue(enricher.candidates("http://192.168.1.1/story").isEmpty())
        assertEquals(0, fetches)
        assertEquals(listOf(image), enricher.candidates(url))
        assertEquals(1, fetches)
    }

    @Test fun sameUrlConcurrentRequestsShareOneFetch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val enricher = ArticleImageEnricher(fetch = { calls.incrementAndGet(); started.complete(Unit); release.await(); listOf(image) })
        val requests = (1..6).map { async { enricher.candidates(url) } }
        started.await()
        release.complete(Unit)
        assertTrue(requests.awaitAll().all { it == listOf(image) })
        assertEquals(1, calls.get())
    }

    @Test fun atMostTwoPageFetchesAreActive() = runBlocking {
        val startedTwice = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val enricher = ArticleImageEnricher(fetch = {
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            if (current == 2) startedTwice.complete(Unit)
            release.await()
            active.decrementAndGet()
            listOf(image)
        })
        val requests = (1..8).map { async { enricher.candidates("https://example.com/$it") } }
        startedTwice.await(); release.complete(Unit); requests.awaitAll()
        assertEquals(2, peak.get())
    }

    @Test fun noNetworkModeUsesCacheButNeverStartsOrNegativeCachesAFetch() = runBlocking {
        var calls = 0
        val enricher = ArticleImageEnricher(fetch = { calls++; listOf(image) })
        assertTrue(enricher.candidates(url, allowNetwork = false).isEmpty())
        assertEquals(0, calls)
        assertEquals(listOf(image), enricher.candidates(url))
        assertEquals(listOf(image), enricher.candidates(url, allowNetwork = false))
        assertEquals(1, calls)
    }

    @Test fun offlineFailuresDoNotPoisonRetryAfterReconnect() = runBlocking {
        var calls = 0
        val enricher = ArticleImageEnricher(fetch = { if (++calls == 1) throw java.net.UnknownHostException("offline"); listOf(image) })
        assertTrue(enricher.candidates(url).isEmpty())
        assertEquals(listOf(image), enricher.candidates(url))
        assertEquals(2, calls)
        assertTrue(isTransientArticleImageFailure(java.net.SocketTimeoutException("timeout")))
        assertFalse(isTransientArticleImageFailure(IOException("corrupt photo")))
    }

    @Test fun cancellationDoesNotPoisonCacheOrLeaveBackgroundWork() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val enricher = ArticleImageEnricher(fetch = {
            if (calls.incrementAndGet() == 1) { started.complete(Unit); CompletableDeferred<Unit>().await() }
            listOf(image)
        })
        val request = launch { enricher.candidates(url) }
        started.await(); request.cancelAndJoin()
        assertEquals(listOf(image), enricher.candidates(url))
        assertEquals(2, calls.get())
    }
}
