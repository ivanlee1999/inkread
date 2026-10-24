package me.ash.reader.infrastructure.rss

import android.content.Context
import java.util.Date
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.service.AccountService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class ReaderCacheHelperTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun article(id: String = "1:article", accountId: Int = 1) = Article(
        id = id, accountId = accountId, date = Date(0), title = "Title", rawDescription = "Summary",
        shortDescription = "Summary", link = "https://example.com/article", feedId = "feed",
    )
    private fun helper(rss: RssHelper, accounts: AccountService = mock()) = ReaderCacheHelper(
        mock<Context> { on { cacheDir } doReturn temporary.root; on { filesDir } doReturn temporary.root }, Dispatchers.IO, rss, accounts,
    )

    @Test fun `browser text is a separate account scoped source and preserves extraction`() = runBlocking<Unit> {
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenReturn("<p>Publisher original</p>")
        val cache = helper(rss)
        cache.readOrFetchFullContent(article()).getOrThrow()
        val imported = cache.importBrowserText("1:article", 1, "公开文章正文。\n\nVisible second paragraph.").getOrThrow()
        assertTrue(imported.contains("公开文章正文"))
        assertEquals(imported, cache.readBrowserText("1:article", 1).getOrThrow())
        assertTrue(cache.readBrowserText("1:article", 2).isFailure)
        assertEquals("<p>Publisher original</p>", cache.readFullContent("1:article", 1).getOrThrow())
        cache.importBrowserText("1:article", 1, "An explicitly imported replacement.").getOrThrow()
        assertTrue(temporary.root.walk().any { it.isFile && it.parentFile.name.endsWith("-versions") && it.readText() == imported })
        assertTrue(cache.importBrowserText("1:article", 1, "https://example.com/article").isFailure)
        assertTrue(cache.readBrowserText("1:article", 1).getOrThrow().contains("replacement"))
    }

    @Test fun `every archived browser source remains reachable through explicit cycling`() = runBlocking<Unit> {
        val cache = helper(mock())
        val first = cache.importBrowserText("item", 1, "Original visible text.").getOrThrow()
        val second = cache.importBrowserText("item", 1, "Second visible text.").getOrThrow()
        cache.importBrowserText("item", 1, "Third visible text.").getOrThrow()
        val earlier = cache.readEarlierContent("item", 1, true, null).getOrThrow()
        val next = cache.readEarlierContent("item", 1, true, earlier).getOrThrow()
        assertEquals(setOf(first, second), setOf(earlier, next))
        assertEquals(earlier, cache.readEarlierContent("item", 1, true, next).getOrThrow())
    }

    @Test fun `concurrent imports serialize archive and commit without losing accepted versions`() = runBlocking<Unit> {
        val cache = helper(mock())
        val values = listOf("First concurrent visible article.", "Second concurrent visible article.")
        val imported = values.map { value -> async(Dispatchers.IO) { cache.importBrowserText("item", 1, value).getOrThrow() } }.awaitAll()
        val current = cache.readBrowserText("item", 1).getOrThrow()
        val previous = cache.readPreviousContent("item", 1, true).getOrThrow()
        assertEquals(imported.toSet(), setOf(current, previous))
    }

    @Test fun `hashing is deterministic under concurrent readers and keeps existing filenames`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.html", readerCacheFileName("abc"))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val expected = (0..200).associateWith { readerCacheFileName(it.toString()) }
            val jobs = (0..2000).map { n -> executor.submit<String> { readerCacheFileName((n % 201).toString()) } }
            jobs.forEachIndexed { n, result -> assertEquals(expected[n % 201], result.get()) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `switching accounts during fetch keeps content in its original account`() = runBlocking<Unit> {
        val accounts: AccountService = mock()
        var current = 1
        whenever(accounts.getCurrentAccountId()).thenAnswer { current }
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenAnswer { current = 2; "<p>Complete</p>" }
        val cache = helper(rss, accounts)
        assertEquals("<p>Complete</p>", cache.readOrFetchFullContent(article()).getOrThrow())
        assertTrue(cache.readFullContent("1:article", 1).isSuccess)
        assertTrue(cache.readFullContent("1:article", 2).isFailure)
        assertEquals(0, temporary.root.walk().count { it.extension == "tmp" })
    }

    @Test fun `concurrent requests fetch once and never publish partial content`() = runBlocking<Unit> {
        val rss: RssHelper = mock()
        val content = "<p>${"x".repeat(200_000)}</p>"
        whenever(rss.parseFullContent(any(), any())).thenReturn(content)
        val cache = helper(rss)
        val results = (1..12).map { async(Dispatchers.IO) { cache.readOrFetchFullContent(article()).getOrThrow() } }.awaitAll()
        results.forEach { assertEquals(content, it) }
        verify(rss, times(1)).parseFullContent(any(), any())
        assertEquals(content, cache.readFullContent("1:article", 1).getOrThrow())
    }

    @Test fun `explicit refresh replaces a cached extraction only on success`() = runBlocking<Unit> {
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenReturn("old content", "new content")
        val cache = helper(rss)
        assertEquals("old content", cache.readOrFetchFullContent(article()).getOrThrow())
        assertEquals("new content", cache.refreshFullContent(article()).getOrThrow())
        assertEquals("new content", cache.readFullContent("1:article", 1).getOrThrow())
        verify(rss, times(2)).parseFullContent(any(), any())
    }

    @Test fun `worse refresh does not overwrite useful offline extraction`() = runBlocking<Unit> {
        val rss: RssHelper = mock()
        val previous = "A complete offline article with useful content and details."
        whenever(rss.parseFullContent(any(), any())).thenReturn(previous, "Oops")
        val cache = helper(rss)
        cache.readOrFetchFullContent(article()).getOrThrow()
        assertEquals(previous, cache.refreshFullContent(article()).getOrThrow())
        assertEquals(previous, cache.readFullContent("1:article", 1).getOrThrow())
    }

    @Test fun `failed explicit refresh retains offline cache`() = runBlocking<Unit> {
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenReturn("offline article")
        val cache = helper(rss)
        cache.readOrFetchFullContent(article()).getOrThrow()
        whenever(rss.parseFullContent(any(), any())).thenThrow(java.io.IOException("offline"))
        assertTrue(cache.refreshFullContent(article()).isFailure)
        assertEquals("offline article", cache.readFullContent("1:article", 1).getOrThrow())
    }

    @Test fun `empty cache is refetched and deletion returns the actual result`() = runBlocking<Unit> {
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenReturn("content")
        val file = temporary.root.resolve("readability/1/${readerCacheFileName("1:article")}")
        file.parentFile.mkdirs()
        file.writeText("")
        val cache = helper(rss)
        assertTrue(cache.readFullContent("1:article", 1).isFailure)
        assertEquals("content", cache.readOrFetchFullContent(article()).getOrThrow())
        assertTrue(cache.deleteCacheFor("1:article", 1))
        assertFalse(cache.deleteCacheFor("1:article", 1))
    }

    @Test fun `cancellation propagates instead of becoming a cached error`() = runBlocking<Unit> {
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenThrow(CancellationException("navigation"))
        val cache = helper(rss)
        try {
            cache.readOrFetchFullContent(article())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(cache.readFullContent("1:article", 1).isFailure)
    }
}
