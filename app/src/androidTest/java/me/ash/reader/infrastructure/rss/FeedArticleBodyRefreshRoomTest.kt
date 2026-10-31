package me.ash.reader.infrastructure.rss

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises generated Room queries against test-owned databases, never the installed reader DB. */
class FeedArticleBodyRefreshRoomTest {
    private lateinit var database: AndroidDatabase

    @Before fun create() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AndroidDatabase::class.java,
        ).build()
    }

    @After fun close() {
        if (::database.isInitialized) database.close()
    }

    @Test fun bodyUpdatePreservesIdentityStateAndMetadataWithoutDeleteOrInsert() = runBlocking {
        withTimeout(20_000) {
            val original = seedArticle()
            val sentinel = original.copy(id = "71\$sentinel", link = "https://example.test/sentinel")
            database.articleDao().insert(sentinel)
            val rowId = rowId(original.id)
            // A REPLACE would insert, and may delete the old row. Only an UPDATE may succeed.
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_refresh_insert BEFORE INSERT ON article BEGIN SELECT RAISE(ABORT, 'unexpected article insert'); END"
            )
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_refresh_delete BEFORE DELETE ON article BEGIN SELECT RAISE(ABORT, 'unexpected article delete'); END"
            )

            assertEquals(1, replace(original))

            val updated = database.articleDao().queryByAccountAndId(original.accountId, original.id)
            assertEquals(original.copy(rawDescription = NEW_BODY, shortDescription = NEW_DESCRIPTION, img = NEW_IMAGE), updated)
            assertEquals(rowId, rowId(original.id))
            assertEquals(sentinel, database.articleDao().queryByAccountAndId(sentinel.accountId, sentinel.id))
            assertNull(database.articleDao().queryByAccountAndId(72, original.id))
        }
    }

    @Test fun compareAndSetRejectsWrongAccountIdFeedLinkAndStaleBody() = runBlocking {
        withTimeout(20_000) {
            val original = seedArticle()
            val mismatches = listOf(
                original.copy(accountId = 72),
                original.copy(id = "71\$missing"),
                original.copy(feedId = "71\$different-feed"),
                original.copy(link = "https://example.test/different-story"),
                original.copy(rawDescription = "<p>A stale revision.</p>"),
            )
            for (expected in mismatches) {
                assertEquals("Mismatched source must not update a row", 0, replace(expected))
                assertEquals(original, database.articleDao().queryByAccountAndId(original.accountId, original.id))
            }

            assertEquals(1, replace(original))
            assertEquals("A second response based on the old body is stale", 0, replace(original, "<p>Late response.</p>"))
            assertEquals(NEW_BODY, database.articleDao().queryByAccountAndId(original.accountId, original.id)!!.rawDescription)
        }
    }

    @Test fun concurrentRefreshesCommitOnlyOneBodyForTheSameRevision() = runBlocking {
        withTimeout(20_000) {
            val original = seedArticle()
            val bodies = listOf("<p>First revised server body.</p>", "<p>Second revised server body.</p>")
            val results = bodies.map { body ->
                async(Dispatchers.IO) { body to replace(original, body) }
            }.awaitAll()

            assertEquals(listOf(0, 1), results.map { it.second }.sorted())
            val winner = results.single { it.second == 1 }.first
            val updated = database.articleDao().queryByAccountAndId(original.accountId, original.id)!!
            assertEquals(original.copy(rawDescription = winner, shortDescription = NEW_DESCRIPTION, img = NEW_IMAGE), updated)
        }
    }

    @Test fun newsletterFeedsAreExcludedFromPublisherPrefetchEvenWhenFullContentIsEnabled() = runBlocking {
        withTimeout(20_000) {
            val ordinary = seedArticle().copy(isUnread = true)
            database.articleDao().update(ordinary)
            val ordinaryFeed = database.feedDao().queryById(ordinary.feedId)!!
            val newsletterFeed = ordinaryFeed.copy(id = "71\$newsletter", isNewsletter = true, isFullContent = true)
            val disabledFeed = ordinaryFeed.copy(id = "71\$disabled", isFullContent = false)
            database.feedDao().insert(newsletterFeed, disabledFeed)
            database.articleDao().insert(
                ordinary.copy(id = "71\$newsletter-article", feedId = newsletterFeed.id),
                ordinary.copy(id = "71\$disabled-article", feedId = disabledFeed.id),
                ordinary.copy(id = "71\$read-article", isUnread = false),
            )

            val requests = database.articleDao().queryUnreadFullContentArticles(71, 50)

            assertEquals(listOf(ordinary.id), requests.map { it.id })
            assertTrue(database.articleDao().queryUnreadFullContentArticles(72, 50).isEmpty())
            val queued = requests.single()
            assertTrue(database.articleDao().canPrefetchFullContent(queued.accountId, queued.id, queued.link))
            assertFalse(database.articleDao().canPrefetchFullContent(72, queued.id, queued.link))
            assertFalse(database.articleDao().canPrefetchFullContent(queued.accountId, queued.id, "https://example.test/changed"))

            database.feedDao().updateNewsletter(ordinary.accountId, ordinary.feedId, true)

            assertFalse(database.articleDao().canPrefetchFullContent(queued.accountId, queued.id, queued.link))
        }
    }

    @Test fun feedHistorySurvivesReopeningWithAndroidDirectorySync() = runBlocking {
        withTimeout(20_000) {
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val testFiles = targetContext.cacheDir.resolve("feed-source-history-test-${UUID.randomUUID()}")
            check(testFiles.mkdirs())
            val context = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = testFiles
            }
            val body = "<article>\r\n  <p>Exact server body: 中文、“quotes” &amp; details.</p>\n</article>\n"
            try {
                val first = FeedSourceHistory(context, Dispatchers.IO, FeedSourceDirectorySync())
                first.archive("71\$123", 71, body)

                val reopened = FeedSourceHistory(context, Dispatchers.IO, FeedSourceDirectorySync())

                assertEquals(body, reopened.readEarlier("71\$123", 71).getOrThrow())
                assertTrue(reopened.readEarlier("71\$123", 72).isFailure)
            } finally {
                testFiles.deleteRecursively()
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun seedArticle(): Article {
        database.accountDao().insertList(listOf(Account(id = 71, name = "Feed refresh test", type = AccountType.FreshRSS)))
        database.groupDao().insert(Group("71\$group", "Test group", 71))
        database.feedDao().insert(Feed("71\$feed", "Test feed", url = "https://example.test/feed",
            groupId = "71\$group", accountId = 71, isFullContent = true))
        return Article(
            id = "71\$123", date = Date(1_000), title = "Original title", author = "Original author",
            rawDescription = "<p>Original server body.</p>", shortDescription = "Original description",
            fullContent = "Legacy body remains unchanged", img = "https://example.test/original.jpg",
            link = "https://example.test/original-story", feedId = "71\$feed", accountId = 71,
            isUnread = false, isStarred = true, isReadLater = true, updateAt = Date(2_000),
        ).also { database.articleDao().insert(it) }
    }

    private suspend fun replace(expected: Article, body: String = NEW_BODY): Int =
        database.articleDao().replaceFeedBodyIfUnchanged(
            expected.accountId, expected.id, expected.feedId, expected.link, expected.rawDescription,
            body, NEW_DESCRIPTION, NEW_IMAGE,
        )

    private fun rowId(id: String): Long =
        database.openHelper.readableDatabase.query("SELECT rowid FROM article WHERE id = ?", arrayOf(id)).use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    private companion object {
        const val NEW_BODY = "<p>Updated server body with additional reporting.</p>"
        const val NEW_DESCRIPTION = "Updated description"
        const val NEW_IMAGE = "https://example.test/updated.jpg"
    }
}
