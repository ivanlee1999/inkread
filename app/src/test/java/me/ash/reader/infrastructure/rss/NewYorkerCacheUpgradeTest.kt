package me.ash.reader.infrastructure.rss

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFullContentRequest
import me.ash.reader.domain.service.AccountService
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class NewYorkerCacheUpgradeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://www.newyorker.com/books/page-turner/i-am-a-2026-national-book-award-finalist-the-us-denied-my-visa"
    private fun article() = Article(id = "article", date = Date(0), title = "Public article",
        rawDescription = "Summary", shortDescription = "Summary", link = url, feedId = "feed", accountId = 1)
    private fun body(paragraphs: Int) = (1..paragraphs).joinToString("") {
        "<p>Public editorial paragraph $it. ${"This visible passage belongs to the article and retains its order. ".repeat(3)}</p>"
    }
    private fun helper(rss: RssHelper) = ReaderCacheHelper(
        mock<Context> { on { cacheDir } doReturn temporary.root; on { filesDir } doReturn temporary.root },
        Dispatchers.IO, rss, mock<AccountService>(),
    )
    private fun cacheFile(a: Article = article()): File = temporary.root.resolve("readability/${a.accountId}/${readerCacheFileName(a.id)}")
    private fun seed(body: String, a: Article = article()) = cacheFile(a).apply { parentFile.mkdirs(); writeText(body) }

    @Test fun `legacy nine paragraph extraction upgrades to twenty three and retains old and imported sources`() = runBlocking<Unit> {
        // The real reported URL yielded 9p with the published legacy parser and 23p today.
        // Synthetic prose preserves that shape without storing the publisher's full article.
        val old = body(9)
        val fresh = body(23)
        seed(old)
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(url, "Public article")).thenReturn(fresh)
        val cache = helper(rss)
        val imported = cache.importBrowserText(article().id, 1, "Manually imported visible text.").getOrThrow()
        assertEquals(old, cache.readFullContent(article().id, 1).getOrThrow())
        assertTrue(cache.requiresNewYorkerCacheUpgrade(article(), old))
        assertEquals(fresh, cache.readOrFetchFullContent(article()) { true }.getOrThrow())
        assertEquals(23, Jsoup.parse(fresh).select("p").size)
        assertEquals(old, cache.readPreviousContent(article().id, 1, false).getOrThrow())
        assertEquals(imported, cache.readBrowserText(article().id, 1).getOrThrow())
        val restarted = helper(rss)
        assertFalse(restarted.requiresNewYorkerCacheUpgrade(article(), fresh))
        assertEquals(fresh, restarted.readOrFetchFullContent(article()) { true }.getOrThrow())
        verify(rss, times(1)).parseFullContent(any(), any())
    }

    @Test fun `offline upgrade preserves readable cache and never certifies the old body`() = runBlocking<Unit> {
        val old = body(9)
        seed(old)
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenThrow(IOException("Offline"))
        val cache = helper(rss)
        assertTrue(cache.readOrFetchFullContent(article()) { true }.isFailure)
        assertEquals(old, cache.readFullContent(article().id, 1).getOrThrow())
        assertTrue(cache.requiresNewYorkerCacheUpgrade(article(), old))
        assertFalse(File(cacheFile().path + ".newyorker-v1").exists())
    }

    @Test fun `privacy gate and background cache checks never upgrade legacy cached articles`() = runBlocking<Unit> {
        val old = body(9)
        seed(old)
        val rss: RssHelper = mock()
        val cache = helper(rss)
        assertTrue(cache.readOrFetchFullContent(article()) { false }.isFailure)
        val a = article()
        assertTrue(cache.checkOrFetchFullContent(ArticleFullContentRequest(1, a.id, a.title, a.link)) { true })
        assertEquals(old, cache.readFullContent(a.id, 1).getOrThrow())
        verifyNoInteractions(rss)
    }

    @Test fun `proof binds exact account article URL revision and body and fails closed on malformed metadata`() = runBlocking<Unit> {
        val fresh = body(23)
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenReturn(fresh)
        val cache = helper(rss)
        cache.readOrFetchFullContent(article()) { true }.getOrThrow()
        val proof = File(cacheFile().path + ".newyorker-v1")
        val valid = proof.readText()
        assertFalse(cache.requiresNewYorkerCacheUpgrade(article(), fresh))
        assertTrue(cache.requiresNewYorkerCacheUpgrade(article().copy(link = "$url?changed=1"), fresh))
        for (changed in listOf(article().copy(accountId = 2), article().copy(id = "other"))) {
            val target = seed(fresh, changed)
            File(target.path + ".newyorker-v1").writeText(valid)
            assertTrue(cache.requiresNewYorkerCacheUpgrade(changed, fresh))
        }
        // Simulate an interrupted new body/proof commit, then malformed/oversized sidecars.
        cacheFile().writeText(body(9))
        assertTrue(cache.requiresNewYorkerCacheUpgrade(article(), body(9)))
        for (invalid in listOf("", "newyorker-public-v0\n" + valid.substringAfter('\n'), "x".repeat(129))) {
            proof.writeText(invalid)
            assertTrue(cache.requiresNewYorkerCacheUpgrade(article(), fresh))
        }
        verify(rss, times(1)).parseFullContent(any(), any())
    }

    @Test fun `quality rejected fresh result cannot stamp retained legacy body as a new extraction`() = runBlocking<Unit> {
        val old = body(23)
        seed(old)
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenReturn(body(2))
        val cache = helper(rss)
        assertEquals(old, cache.readOrFetchFullContent(article()) { true }.getOrThrow())
        assertEquals(old, cache.readFullContent(article().id, 1).getOrThrow())
        assertTrue(cache.requiresNewYorkerCacheUpgrade(article(), old))
        assertFalse(File(cacheFile().path + ".newyorker-v1").exists())
    }

    @Test fun `late privacy change prevents upgrade write and leaves old body available`() = runBlocking<Unit> {
        val old = body(9)
        seed(old)
        var allowed = true
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenAnswer { allowed = false; body(23) }
        val cache = helper(rss)
        assertTrue(cache.readOrFetchFullContent(article()) { allowed }.isFailure)
        assertEquals(old, cache.readFullContent(article().id, 1).getOrThrow())
        assertTrue(cache.requiresNewYorkerCacheUpgrade(article(), old))
    }

    @Test fun `only exact New Yorker host opts into cache upgrade`() = runBlocking<Unit> {
        val old = body(9)
        val rss: RssHelper = mock()
        val cache = helper(rss)
        for (link in listOf("https://example.com/article", "https://www.newyorker.com.evil.invalid/article", "bad URL")) {
            val a = article().copy(link = link)
            seed(old, a)
            assertFalse(cache.requiresNewYorkerCacheUpgrade(a, old))
            assertEquals(old, cache.readOrFetchFullContent(a) { true }.getOrThrow())
        }
        verifyNoInteractions(rss)
    }
}
