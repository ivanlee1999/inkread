package me.ash.reader.infrastructure.library

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.library.SavedImageState
import me.ash.reader.domain.library.SavedContentState
import me.ash.reader.domain.library.SavedLocation
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

/**
 * Native Room + production repository/files tests. Every DB and directory is uniquely named
 * test-owned state; these tests never open the user's Reader or saved-library database.
 */
class SavedLibraryRoomPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var database: SavedLibraryDatabase
    private val scheduler = RecordingScheduler()
    private val downloader = ControlledDownloader()

    @Before fun createStorage() {
        name = "saved-library-test-${UUID.randomUUID()}.db"
        root = File(context.filesDir, "saved-library-test-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        cache = File(context.cacheDir, "rss-cache-test-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        database = openDatabase()
    }

    @After fun closeStorage() {
        if (::database.isInitialized) database.close()
        if (::name.isInitialized) context.deleteDatabase(name)
        if (::root.isInitialized) root.deleteRecursively()
        if (::cache.isInitialized) cache.deleteRecursively()
    }

    @Test fun durableSnapshotSurvivesRssUnsubscribeAccountDeletionCacheRemovalAndDatabaseReopen() = runBlocking {
        withTimeout(20_000) {
            val rss = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
            try {
                val accountA = Account(id = 51, name = "Test account A", type = AccountType.Local)
                val accountB = Account(id = 52, name = "Test account B", type = AccountType.Local)
                rss.accountDao().insertList(listOf(accountA, accountB))
                rss.groupDao().insert(Group("test-group", "Test group", 51))
                val feed = Feed("test-feed", "Test publication", url = "https://example.test/feed", groupId = "test-group", accountId = 51)
                rss.feedDao().insert(feed)
                downloader.imageBytes = InstrumentationRegistry.getInstrumentation().context.assets.open("nasa-earthrise.jpg").use { it.readBytes() }
                val illustratedHtml = HTML + "<img src=\"https://example.test/nasa-earthrise.jpg\">"
                val source = Article("test-rss-article", Date(1_000), "Durable article", "Ada", illustratedHtml,
                    "Saved beyond the subscription", link = URL, feedId = feed.id, accountId = 51)
                rss.articleDao().insert(source)
                File(cache, "rss-source.html").writeText(HTML)
                val original = repository()
                val id = original.saveRssSnapshot(source.link, source.title, source.author, feed.name, source.rawDescription)
                original.download(id, scheduler.requests.last { it.first == id }.second)
                val before = requireNotNull(original.loadDocument(id))
                assertEquals(SavedImageState.READY, before.item.imageState)
                val retainedPhoto = File(requireNotNull(before.item.thumbnailPath))
                assertTrue(retainedPhoto.canonicalPath.startsWith(root.canonicalPath + File.separator))
                assertArrayEquals(downloader.imageBytes, retainedPhoto.readBytes())
                original.move(id, SavedLocation.ARCHIVE)
                original.setRead(id, true)
                original.saveReadingAnchor(id, before.contentRevision, ANCHOR)

                rss.feedDao().delete(feed) // Actual FK cascade, not a mocked unsubscribe.
                assertNull(rss.articleDao().queryById(source.id))
                rss.accountDao().delete(accountA)
                assertEquals(52, rss.accountDao().queryAll().single().id)
                assertTrue(cache.deleteRecursively())
                assertEquals("Core RSS schema stays at released version", 7, rss.openHelper.readableDatabase.version)
                assertEquals("Saved data uses a separate schema", 1, database.openHelper.readableDatabase.version)

                database.close()
                database = openDatabase()
                downloader.extract = { _, _ -> throw IOException("No network after restart") }
                downloader.imageBytes = null
                val reopened = repository()
                val after = requireNotNull(reopened.loadDocument(id))
                assertEquals(before.html, after.html)
                assertEquals(before.contentRevision, after.contentRevision)
                assertEquals(SavedImageState.READY, after.item.imageState)
                assertEquals(retainedPhoto.absolutePath, after.item.thumbnailPath)
                assertTrue(retainedPhoto.isFile)
                assertEquals(SavedContentState.READY, after.item.contentState)
                assertEquals(SavedLocation.ARCHIVE, after.item.location)
                assertTrue(after.item.isRead)
                assertEquals(ANCHOR, after.readingAnchor)
                assertEquals(id, reopened.observeItems(SavedLocation.ARCHIVE).first().single().id)
                assertTrue(reopened.observeItems(SavedLocation.INBOX).first().isEmpty())
            } finally { rss.close() }
        }
    }

    @Test fun realSqliteUniquenessKeepsConcurrentRepeatedSavesAtOneRowAcrossReopen() = runBlocking {
        withTimeout(20_000) {
            val original = repository()
            val ids = (0 until 48).map { async(Dispatchers.Default) { original.saveUrl(URL, "Concurrent title") } }.awaitAll()
            assertEquals(1, ids.toSet().size)
            assertEquals(1, database.savedLibraryDao().all().size)
            val id = ids.first()
            // Bypass the repository mutex to exercise SQLite's unique normalized-URL index.
            val existing = requireNotNull(database.savedLibraryDao().get(id))
            assertEquals(-1L, database.savedLibraryDao().insert(existing.copy(id = "duplicate-id")))
            assertEquals(1, database.savedLibraryDao().all().size)
            original.move(id, SavedLocation.LATER)
            original.setRead(id, true)
            val savedAt = database.savedLibraryDao().get(id)!!.savedAt
            database.close()
            database = openDatabase()
            val reopened = repository()
            assertEquals(id, reopened.saveUrl("HTTPS://EXAMPLE.TEST:443/article#shared", "Repeated title"))
            assertEquals(1, database.savedLibraryDao().all().size)
            val item = reopened.observeItem(id).first()!!
            assertEquals(savedAt, item.savedAt)
            assertEquals(SavedLocation.LATER, item.location)
            assertTrue(item.isRead)
        }
    }

    @Test fun actualSqliteUpdateCannotResurrectAnItemRemovedDuringExtraction() = runBlocking {
        withTimeout(20_000) {
            val original = repository()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            downloader.extract = { _, _ -> entered.complete(Unit); release.await(); article() }
            val id = original.saveUrl(URL)
            val token = scheduler.requests.last { it.first == id }.second
            val worker = launch(Dispatchers.IO) { original.download(id, token) }
            entered.await()
            original.remove(id)
            release.complete(Unit)
            worker.join()
            assertNull(database.savedLibraryDao().get(id))
            assertNull(original.loadDocument(id))
            database.close()
            database = openDatabase()
            assertTrue(repository().observeItems().first().isEmpty())
        }
    }

    @Test fun cancellationAfterRealSnapshotInsertKeepsCommittedBodyReadableAcrossReopen() = runBlocking {
        withTimeout(20_000) {
            val dao = CancelAfterCommitDao(database.savedLibraryDao(), cancelAfterInsert = true)
            val original = repository(dao)
            assertCallerCancelledAtCommit(dao) {
                original.saveRssSnapshot(URL, "Durable article", "Ada", "Test publication", HTML)
            }

            val committed = database.savedLibraryDao().all().single()
            assertEquals(SavedContentState.READY.name, committed.contentState)
            assertEquals(SavedImageState.NONE.name, committed.imageState)
            val revision = requireNotNull(committed.fileRevision)
            val expected = SavedDocumentImages.rewrite(HTML, URL, emptyMap())
            // Read files directly first: loadDocument must not repair or hide a dangling Room row.
            assertEquals(expected, SavedLibraryFiles(root).read(committed.id, revision))
            assertEquals(expected, requireNotNull(original.loadDocument(committed.id)).html)

            database.close()
            database = openDatabase()
            val reopened = requireNotNull(repository().loadDocument(committed.id))
            assertEquals(expected, reopened.html)
            assertEquals(committed.contentRevision, reopened.contentRevision)
            assertEquals(SavedContentState.READY, reopened.item.contentState)
        }
    }

    @Test fun cancellationAfterRealUrlInsertStillEnqueuesCommittedTokenBeforeRecovery() = runBlocking {
        withTimeout(20_000) {
            val dao = CancelAfterCommitDao(database.savedLibraryDao(), cancelAfterInsert = true)
            val original = repository(dao)
            assertCallerCancelledAtCommit(dao) { original.saveUrl(URL, "Queued article") }

            val committed = database.savedLibraryDao().all().single()
            assertEquals(SavedContentState.QUEUED.name, committed.contentState)
            val token = requireNotNull(committed.downloadToken)
            // No observe/reopen recovery may mask an interrupted insert-to-enqueue boundary.
            assertEquals(listOf(committed.id to token), scheduler.requests.toList())
            assertNull(committed.contentRevision)

            database.close()
            database = openDatabase()
            assertEquals(committed, database.savedLibraryDao().all().single())
            assertEquals(listOf(committed.id to token), scheduler.requests.toList())
        }
    }

    @Test fun cancellationAfterRealTextRevisionUpdateKeepsPublishedBodyAcrossReopen() = runBlocking {
        withTimeout(20_000) {
            val dao = CancelAfterCommitDao(database.savedLibraryDao(), cancelAfterUpdate = { it.contentRevision != null })
            val original = repository(dao)
            val id = original.saveUrl(URL)
            val token = scheduler.requests.single().second
            assertCallerCancelledAtCommit(dao) { original.download(id, token) }

            val committed = requireNotNull(database.savedLibraryDao().get(id))
            assertEquals(SavedContentState.READY.name, committed.contentState)
            val revision = requireNotNull(committed.fileRevision)
            val expected = SavedDocumentImages.rewrite(HTML, URL, emptyMap())
            assertEquals(expected, SavedLibraryFiles(root).read(id, revision))
            assertEquals(expected, requireNotNull(original.loadDocument(id)).html)

            database.close()
            database = openDatabase()
            val reopened = requireNotNull(repository().loadDocument(id))
            assertEquals(expected, reopened.html)
            assertEquals(committed.contentRevision, reopened.contentRevision)
            assertEquals(SavedContentState.READY, reopened.item.contentState)
        }
    }

    @Test fun cancellationAfterRealImageRevisionUpdateKeepsPublishedBodyAndImageAcrossReopen() = runBlocking {
        withTimeout(20_000) {
            val imageBytes = InstrumentationRegistry.getInstrumentation().context.assets.open("nasa-earthrise.jpg").use { it.readBytes() }
            downloader.imageBytes = imageBytes
            val seed = repository()
            val id = seed.saveRssSnapshot(URL, "Durable article", "Ada", "Test publication",
                HTML + "<img src=\"https://example.test/nasa-earthrise.jpg\">")
            val before = requireNotNull(database.savedLibraryDao().get(id))
            val dao = CancelAfterCommitDao(database.savedLibraryDao(), cancelAfterUpdate = { it.fileRevision != before.fileRevision })
            val original = repository(dao)
            assertCallerCancelledAtCommit(dao) { original.download(id, requireNotNull(before.downloadToken)) }

            val committed = requireNotNull(database.savedLibraryDao().get(id))
            assertEquals(SavedContentState.READY.name, committed.contentState)
            assertEquals(SavedImageState.READY.name, committed.imageState)
            assertEquals(before.contentRevision, committed.contentRevision)
            assertNotEquals(before.fileRevision, committed.fileRevision)
            assertNull(committed.downloadToken)
            val revision = requireNotNull(committed.fileRevision)
            val photo = File(requireNotNull(committed.thumbnailPath))
            assertTrue(photo.canonicalPath.startsWith(root.canonicalPath + File.separator))
            assertArrayEquals(imageBytes, photo.readBytes())
            val html = requireNotNull(SavedLibraryFiles(root).read(id, revision))
            assertTrue(html.contains(photo.toURI().toString()))
            assertEquals(html, requireNotNull(original.loadDocument(id)).html)

            database.close()
            database = openDatabase()
            downloader.imageBytes = null
            val reopened = requireNotNull(repository().loadDocument(id))
            assertEquals(html, reopened.html)
            assertEquals(before.contentRevision, reopened.contentRevision)
            assertEquals(SavedImageState.READY, reopened.item.imageState)
            assertEquals(photo.absolutePath, reopened.item.thumbnailPath)
            assertArrayEquals(imageBytes, photo.readBytes())
        }
    }

    private fun openDatabase() = Room.databaseBuilder(context, SavedLibraryDatabase::class.java, name).build()
    private fun repository(dao: SavedLibraryDao = database.savedLibraryDao()) =
        DefaultSavedLibraryRepository(dao, SavedLibraryFiles(root), downloader, scheduler)

    private suspend fun assertCallerCancelledAtCommit(dao: CancelAfterCommitDao, action: suspend () -> Unit) = coroutineScope {
        val caller = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            action()
            fail("Repository must not return success to a cancelled caller")
        }
        dao.caller = caller
        caller.start()
        caller.join()
        assertEquals("The real Room commit must reach the cancellation boundary", 1, dao.cancellationCount)
        assertTrue("The caller must still observe cancellation", caller.isCancelled)
    }

    /** Cancel only after Room has committed, before delivering the suspending DAO result. */
    private class CancelAfterCommitDao(
        private val delegate: SavedLibraryDao,
        private val cancelAfterInsert: Boolean = false,
        private val cancelAfterUpdate: (SavedLibraryEntity) -> Boolean = { false },
    ) : SavedLibraryDao by delegate {
        lateinit var caller: Job
        var cancellationCount = 0
            private set

        override suspend fun insert(item: SavedLibraryEntity): Long {
            val result = delegate.insert(item)
            if (result != -1L && cancelAfterInsert) cancelCaller()
            return result
        }

        override suspend fun update(item: SavedLibraryEntity): Int {
            val result = delegate.update(item)
            if (result > 0 && cancelAfterUpdate(item)) cancelCaller()
            return result
        }

        private suspend fun cancelCaller() {
            if (cancellationCount != 0) return
            cancellationCount++
            caller.cancel(CancellationException("Test cancellation after a committed Room write"))
            // A protected commit may finish, but a cancellable DAO delivery throws here.
            currentCoroutineContext().ensureActive()
        }
    }

    private class ControlledDownloader : SavedArticleDownloader {
        var extract: suspend (String, String?) -> SavedExtractedArticle = { _, _ -> article() }
        override suspend fun extract(url: String, titleHint: String?) = extract.invoke(url, titleHint)
        var imageBytes: ByteArray? = null
        override suspend fun fetchImage(url: String): ByteArray = imageBytes ?: error("No network or image fetch is allowed after reopen")
    }

    private class RecordingScheduler : SavedDownloadScheduler {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        override fun enqueue(id: String, token: String, replace: Boolean) { requests += id to token }
        override fun cancel(id: String) = Unit
    }

    companion object {
        private const val URL = "https://example.test/article"
        private const val HTML = "<article><h1>Durable article</h1><p>This text remains readable after unsubscribing and changing RSS accounts.</p></article>"
        private const val ANCHOR = "{\"block\":1,\"offset\":9}"
        private fun article() = SavedExtractedArticle(HTML, "Durable article", "Ada", "Test publication", "Saved beyond the subscription")
    }
}
