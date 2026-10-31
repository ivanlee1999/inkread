package me.ash.reader.infrastructure.rss

import android.content.Context
import java.io.IOException
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderDTO
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class FeedArticleBodyRefreshTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = "<p>A complete first paragraph with useful reporting, factual detail, and a clear ending.</p>"
    private val expanded get() = original + "<p>A second paragraph adds the latest public development and explains its significance.</p>"

    @Test fun existingIdIsRefetchedWithoutAnySyncCursorAndOnlyContentChanges() = runBlocking {
        val fixture = Fixture()
        val before = fixture.row.copy()
        val requested = mutableListOf<String>()
        val result = fixture.refresher.refresh(before) { id ->
            requested += id
            // Concurrent reader actions must survive the content-only update.
            fixture.row = fixture.row.copy(isUnread = false, isStarred = true, isReadLater = true)
            fixture.response(expanded)
        }
        assertEquals(listOf("123"), requested)
        assertEquals(FeedArticleRefreshStatus.UPDATED, result.status)
        assertEquals(expanded, result.article.rawDescription)
        assertEquals(before.copy(rawDescription = expanded, shortDescription = result.article.shortDescription,
            isUnread = false, isStarred = true, isReadLater = true), result.article)
        assertEquals(original, fixture.history.readEarlier(before.id, before.accountId).getOrThrow())
        assertTrue("Old exact body is archived before SQL executes", fixture.archiveExistedAtUpdate)
        verify(fixture.dao, never()).insert(any())
        verify(fixture.dao, never()).update(any())
    }

    @Test fun staleBodyCompareAndSetRetainsConcurrentRevision() = runBlocking {
        val fixture = Fixture()
        val concurrent = "<p>A separately accepted server revision.</p>"
        val result = fixture.refresher.refresh(fixture.row.copy()) {
            fixture.row = fixture.row.copy(rawDescription = concurrent)
            fixture.response(expanded)
        }
        assertEquals(FeedArticleRefreshStatus.CONCURRENT_CHANGE, result.status)
        assertEquals(concurrent, result.article.rawDescription)
        assertEquals(original, fixture.history.readEarlier(result.article.id, 7).getOrThrow())
    }

    @Test fun unchangedOrTruncatedBodyDoesNotWriteOrArchive() = runBlocking {
        for ((body, status) in listOf(original to FeedArticleRefreshStatus.UNCHANGED,
            "<p>Subscribe</p>" to FeedArticleRefreshStatus.KEPT_CURRENT)) {
            val fixture = Fixture()
            val result = fixture.refresher.refresh(fixture.row) { fixture.response(body) }
            assertEquals(status, result.status)
            assertEquals(original, result.article.rawDescription)
            assertEquals(0, fixture.updateCalls)
            assertTrue(fixture.history.readEarlier(fixture.row.id, 7).isFailure)
        }
    }

    @Test fun archiveFailureLeavesBodyAndStatusUntouched() = runBlocking {
        temporary.root.resolve("reader-feed-sources").writeText("A file blocks archive creation")
        val fixture = Fixture()
        val before = fixture.row.copy()
        try {
            fixture.refresher.refresh(before) { fixture.response(expanded) }
            fail("Expected history write failure")
        } catch (_: IOException) { }
        catch (_: IllegalStateException) { }
        assertEquals(before, fixture.row)
        assertEquals(0, fixture.updateCalls)
    }

    @Test fun wrongAccountMissingRowWrongItemOrWrongFeedCannotModifyArticle() = runBlocking {
        val fixture = Fixture()
        val before = fixture.row.copy()
        try {
            fixture.refresher.refresh(before.copy(accountId = 8)) { error("Wrong account must not fetch") }
            fail("Expected missing account-scoped article")
        } catch (_: IOException) { }
        for (response in listOf(
            fixture.response(expanded, "124"),
            fixture.response(expanded, feed = "feed/elsewhere"),
            GoogleReaderDTO.ItemsContents(items = emptyList()),
            fixture.response(null),
            fixture.response("中".repeat((MAX_FEED_BODY_BYTES / 3 + 1).toInt())),
        )) {
            try {
                fixture.refresher.refresh(before) { response }
                fail("Expected invalid response rejection")
            } catch (_: IOException) { }
        }
        assertEquals(before, fixture.row)
        assertEquals(0, fixture.updateCalls)
    }

    @Test fun cancellationNeverPublishesFetchedBody() = runBlocking {
        val fixture = Fixture()
        val before = fixture.row.copy()
        try {
            fixture.refresher.refresh(before) { throw CancellationException("Reader navigation") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(before, fixture.row)
        assertEquals(0, fixture.updateCalls)
        assertTrue(fixture.history.readEarlier(before.id, before.accountId).isFailure)
    }

    @Test fun failedDirectorySyncPreventsBodyReplacement() = runBlocking {
        val sync: FeedSourceDirectorySync = mock()
        doAnswer { throw IOException("Directory sync failed") }.whenever(sync).sync(any())
        val fixture = Fixture(sync)
        val before = fixture.row.copy()
        try {
            fixture.refresher.refresh(before) { fixture.response(expanded) }
            fail("Archive directory must be durable first")
        } catch (_: IOException) { }
        assertEquals(before, fixture.row)
        assertEquals(0, fixture.updateCalls)
    }

    @Test fun invalidServerIdsCannotStartARequest() = runBlocking {
        for (id in listOf("7\$tag:google.com,2005:reader/item/zzzzzzzzzzzzzzzz", "7\$not-a-server-id")) {
            val fixture = Fixture()
            fixture.row = fixture.row.copy(id = id)
            try {
                fixture.refresher.refresh(fixture.row.copy()) { error("Invalid identity must not fetch") }
                fail("Expected invalid server ID")
            } catch (_: IOException) { }
            assertEquals(0, fixture.updateCalls)
        }
    }

    @Test fun concurrentChangeIsReportedEvenWhenResponseWouldNotWrite() = runBlocking {
        for (responseBody in listOf(original, "<p>Truncated</p>")) {
            val fixture = Fixture()
            val before = fixture.row.copy()
            val concurrentBody = "<p>Another accepted revision.</p>"
            val result = fixture.refresher.refresh(before) {
                fixture.row = fixture.row.copy(rawDescription = concurrentBody)
                fixture.response(responseBody)
            }
            assertEquals(FeedArticleRefreshStatus.CONCURRENT_CHANGE, result.status)
            assertEquals(concurrentBody, result.article.rawDescription)
            assertEquals(0, fixture.updateCalls)
        }
    }

    private inner class Fixture(directorySync: FeedSourceDirectorySync = mock()) {
        val dao: ArticleDao = mock()
        val history = FeedSourceHistory(mock<Context> { on { filesDir } doReturn temporary.root }, Dispatchers.IO, directorySync)
        val refresher = FeedArticleBodyRefresh(dao, history, Dispatchers.IO)
        var row = Article("7\$123", Date(1_000), "Existing title", "Existing author", original, "Previous summary",
            img = "https://example.test/photo.jpg", link = "https://example.test/original", feedId = "7\$source", accountId = 7,
            updateAt = Date(2_000))
        var updateCalls = 0
        var archiveExistedAtUpdate = false

        init {
            runBlocking {
                whenever(dao.queryByAccountAndId(any(), any())).thenAnswer { invocation ->
                    row.takeIf { it.accountId == invocation.getArgument<Int>(0) && it.id == invocation.getArgument<String>(1) }?.copy()
                }
                whenever(dao.replaceFeedBodyIfUnchanged(any(), any(), any(), any(), any(), any(), any(), anyOrNull())).thenAnswer { call ->
                    updateCalls++
                    archiveExistedAtUpdate = temporary.root.walk().any { it.extension == "html" && it.readText() == original }
                    if (row.accountId == call.getArgument<Int>(0) && row.id == call.getArgument<String>(1) &&
                        row.feedId == call.getArgument<String>(2) && row.link == call.getArgument<String>(3) &&
                        row.rawDescription == call.getArgument<String>(4)) {
                        row = row.copy(rawDescription = call.getArgument(5), shortDescription = call.getArgument(6), img = call.getArgument(7))
                        1
                    } else 0
                }
            }
        }

        fun response(body: String?, id: String = "tag:google.com,2005:reader/item/000000000000007b", feed: String = "feed/source") =
            GoogleReaderDTO.ItemsContents(items = listOf(GoogleReaderDTO.Item(id = id,
                summary = GoogleReaderDTO.Summary(body), origin = GoogleReaderDTO.OriginItem(streamId = feed),
                title = "Changed server title", author = "Changed server author", published = 9_999,
                categories = listOf("user/-/state/com.google/read"),
                canonical = listOf(GoogleReaderDTO.CanonicalItem("https://example.test/different")))))
    }
}
