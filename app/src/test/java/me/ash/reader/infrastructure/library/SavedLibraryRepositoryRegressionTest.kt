package me.ash.reader.infrastructure.library

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.library.SavedContentState
import me.ash.reader.domain.library.SavedImageState
import me.ash.reader.domain.library.SavedLocation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the production repository and its actual durable files. Only the DAO transport,
 * remote downloader and WorkManager scheduler are controlled. Room persistence/reopen is
 * additionally exercised by SavedLibraryRoomPersistenceTest on Android.
 */
class SavedLibraryRepositoryRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun concurrentRepeatedSavesKeepOneIdentityAndDoNotResetArchiveReadOrAnchor() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val ids = (0 until 64).map { async(Dispatchers.Default) { repository.saveUrl(URL, "Shared title") } }.awaitAll()
        assertEquals("Concurrent duplicate requests must share the durable identity", 1, ids.toSet().size)
        val id = ids.first()
        repository.download(id, fixture.scheduler.latest(id).token)
        val first = requireNotNull(repository.loadDocument(id))
        repository.move(id, SavedLocation.ARCHIVE)
        repository.setRead(id, true)
        repository.saveReadingAnchor(id, first.contentRevision, ANCHOR)
        val savedAt = fixture.dao.get(id)!!.savedAt

        (0 until 64).map { index -> async(Dispatchers.Default) {
            if (index % 2 == 0) repository.saveUrl(URL, "A repeated title")
            else repository.saveRssSnapshot(URL, "A repeated snapshot", null, null, "<p>Replacement</p>")
        } }.awaitAll().forEach { assertEquals(id, it) }

        val after = requireNotNull(repository.loadDocument(id))
        assertEquals(1, fixture.dao.all().size)
        assertEquals(SavedLocation.ARCHIVE, after.item.location)
        assertTrue(after.item.isRead)
        assertEquals(savedAt, after.item.savedAt)
        assertEquals("Saving again does not discard the reader's position", ANCHOR, after.readingAnchor)
        assertEquals("Saving again does not replace a readable snapshot", first.contentRevision, after.contentRevision)
        assertEquals(first.html, after.html)
    }

    @Test fun locationAndReadAreIndependentAndObservedListsAreExclusive() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val inbox = repository.saveUrl("https://example.test/inbox")
        val later = repository.saveUrl("https://example.test/later")
        val archive = repository.saveUrl("https://example.test/archive")
        repository.move(later, SavedLocation.LATER)
        repository.move(archive, SavedLocation.ARCHIVE)
        repository.setRead(inbox, true)
        repository.setRead(later, true)

        assertEquals(listOf(inbox), repository.observeItems(SavedLocation.INBOX).first().map { it.id })
        assertEquals(listOf(later), repository.observeItems(SavedLocation.LATER).first().map { it.id })
        assertEquals(listOf(archive), repository.observeItems(SavedLocation.ARCHIVE).first().map { it.id })
        assertFalse("Archiving alone never invents a read event", repository.observeItem(archive).first()!!.isRead)
        repository.move(inbox, SavedLocation.ARCHIVE)
        assertTrue(repository.observeItem(inbox).first()!!.isRead)
        repository.setRead(later, false)
        assertEquals(SavedLocation.LATER, repository.observeItem(later).first()!!.location)
        assertEquals(3, repository.observeItems().first().size)
    }

    @Test fun textRemainsOfflineAfterRepositoryRecreationWithoutNetwork() = runRepositoryTest {
        val fixture = Fixture()
        val first = fixture.repository(Dispatchers.Unconfined)
        val id = first.saveRssSnapshot(URL, "Offline article", "Ada", "Paper", HTML)
        val document = requireNotNull(first.loadDocument(id))
        fixture.downloader.extract = { _, _ -> error("Offline load must not request a remote article") }
        fixture.downloader.image = { error("Offline load must not request an image") }
        val reopened = fixture.repository(Dispatchers.Unconfined)
        val offline = requireNotNull(reopened.loadDocument(id))
        assertEquals(SavedContentState.READY, offline.item.contentState)
        assertEquals(document.html, offline.html)
        assertEquals(document.contentRevision, offline.contentRevision)
        assertTrue(offline.html.contains("A durable paragraph"))
    }

    @Test fun failedDownloadRetainsTheItemAndRetryCanProduceDurableText() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        fixture.downloader.extract = { _, _ -> throw IOException("Deterministic unavailable source") }
        val id = repository.saveUrl(URL)
        val failedToken = fixture.scheduler.latest(id).token
        repository.download(id, failedToken)
        val failed = repository.observeItem(id).first()!!
        assertEquals(SavedContentState.FAILED, failed.contentState)
        assertFalse(failed.lastError.isNullOrBlank())
        assertNull(repository.loadDocument(id))

        fixture.downloader.extract = { _, _ -> article(HTML) }
        repository.move(id, SavedLocation.LATER)
        repository.setRead(id, true)
        repository.retry(id)
        val retry = fixture.scheduler.latest(id)
        assertNotEquals("Retry invalidates the failed download token", failedToken, retry.token)
        repository.download(id, retry.token)
        val document = requireNotNull(repository.loadDocument(id))
        assertEquals(SavedContentState.READY, document.item.contentState)
        assertEquals(SavedLocation.LATER, document.item.location)
        assertTrue(document.item.isRead)
        assertNull(document.item.lastError)
    }

    @Test fun unavailableImagesDoNotDiscardReadableOfflineText() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        fixture.downloader.extract = { _, _ -> article("$HTML<img src=\"https://example.test/missing.png\">") }
        fixture.downloader.image = { throw IOException("Deterministic missing image") }
        val id = repository.saveUrl(URL)
        repository.download(id, fixture.scheduler.latest(id).token)
        val document = requireNotNull(repository.loadDocument(id))
        assertEquals("Text readiness is not the same as image completeness", SavedContentState.READY, document.item.contentState)
        assertTrue(document.item.imageState == SavedImageState.FAILED || document.item.imageState == SavedImageState.PARTIAL)
        assertTrue(document.html.contains("A durable paragraph"))
    }

    @Test fun removalWhileExtractionIsSuspendedCannotResurrectItemOrDocument() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.downloader.extract = { _, _ -> entered.complete(Unit); release.await(); article(HTML) }
        val id = repository.saveUrl(URL)
        val token = fixture.scheduler.latest(id).token
        val worker = launch(Dispatchers.Unconfined) { repository.download(id, token) }
        entered.await()
        repository.remove(id)
        release.complete(Unit)
        worker.join()
        assertNull(repository.observeItem(id).first())
        assertNull(repository.loadDocument(id))
        assertTrue(fixture.dao.all().isEmpty())
        assertTrue("Removing cancels its queued/background work", id in fixture.scheduler.cancelled)
        // A delayed delivery of the very same WorkManager request is equally harmless.
        repository.download(id, token)
        assertTrue(fixture.dao.all().isEmpty())
    }

    @Test fun supersededDownloadCannotOverwriteNewerRetryOrUserState() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var extraction = 0
        fixture.downloader.extract = { _, _ ->
            extraction++
            if (extraction == 1) { entered.complete(Unit); release.await(); article("<p>Old result</p>") }
            else article("<p>New result</p>")
        }
        val id = repository.saveUrl(URL)
        val oldToken = fixture.scheduler.latest(id).token
        val oldWorker = launch(Dispatchers.Unconfined) { repository.download(id, oldToken) }
        entered.await()
        repository.retry(id)
        val newToken = fixture.scheduler.latest(id).token
        assertNotEquals(oldToken, newToken)
        repository.move(id, SavedLocation.ARCHIVE)
        repository.setRead(id, true)
        repository.download(id, newToken)
        release.complete(Unit)
        oldWorker.join()
        val document = requireNotNull(repository.loadDocument(id))
        assertTrue(document.html.contains("New result"))
        assertFalse(document.html.contains("Old result"))
        assertEquals(SavedLocation.ARCHIVE, document.item.location)
        assertTrue(document.item.isRead)
    }

    @Test fun cancelledDownloadCanBeRetriedAndStaleAnchorRevisionIsIgnored() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        fixture.downloader.extract = { _, _ -> entered.complete(Unit); CompletableDeferred<Unit>().await(); article(HTML) }
        val id = repository.saveUrl(URL)
        val interrupted = launch(Dispatchers.Unconfined) {
            repository.download(id, fixture.scheduler.latest(id).token)
        }
        entered.await()
        interrupted.cancelAndJoin()
        assertNotNull("Cancellation must retain the user's saved request", repository.observeItem(id).first())
        fixture.downloader.extract = { _, _ -> article(HTML) }
        repository.retry(id)
        repository.download(id, fixture.scheduler.latest(id).token)
        val document = requireNotNull(repository.loadDocument(id))
        repository.saveReadingAnchor(id, document.contentRevision, ANCHOR)
        repository.saveReadingAnchor(id, "obsolete-document-revision", "stale anchor")
        assertEquals(ANCHOR, repository.loadDocument(id)!!.readingAnchor)
    }

    @Test fun persistedQueuedRequestIsRecoveredOnceWhenTheLibraryIsObservedAgain() = runRepositoryTest {
        val fixture = Fixture()
        val original = fixture.repository(Dispatchers.Unconfined)
        val id = original.saveUrl(URL)
        val queued = fixture.scheduler.latest(id)
        val requestsBeforeRestart = fixture.scheduler.requests.size
        val restarted = fixture.repository(Dispatchers.Unconfined)
        assertEquals(id, restarted.observeItems().first().single().id)
        assertEquals(requestsBeforeRestart + 1, fixture.scheduler.requests.size)
        assertEquals(queued.token, fixture.scheduler.latest(id).token)
        restarted.observeItem(id).first()
        restarted.observeItems(SavedLocation.INBOX).first()
        assertEquals("Subsequent collectors do not continually enqueue the same job", requestsBeforeRestart + 1, fixture.scheduler.requests.size)
    }

    @Test fun readerAnchorWritesDoNotInvalidateMetadataListOrItemFlows() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val id = repository.saveRssSnapshot(URL, "Offline article", "Ada", "Paper", HTML)
        val document = requireNotNull(repository.loadDocument(id))
        val lists = mutableListOf<List<me.ash.reader.domain.library.SavedItem>>()
        val items = mutableListOf<me.ash.reader.domain.library.SavedItem?>()
        val listCollector = launch(Dispatchers.Unconfined) { repository.observeItems().collect { lists += it } }
        val itemCollector = launch(Dispatchers.Unconfined) { repository.observeItem(id).collect { items += it } }
        repeat(20) { repository.saveReadingAnchor(id, document.contentRevision, "{\"block\":$it}") }
        assertEquals("Page turns do not remeasure the Saved newspaper", 1, lists.size)
        assertEquals("Page turns do not reload reader metadata", 1, items.size)
        assertEquals("{\"block\":19}", repository.loadDocument(id)!!.readingAnchor)
        listCollector.cancelAndJoin()
        itemCollector.cancelAndJoin()
    }

    @Test fun imageCompletionKeepsLogicalRevisionAndAnchorWhileDurablyReplacingOnlyFileRevision() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        fixture.downloader.image = { java.util.Base64.getDecoder().decode(TINY_PNG) }
        val id = repository.saveRssSnapshot(URL, "Illustrated article", "Ada", "Paper", "$HTML<img src=\"https://example.test/photo.png\">")
        val text = requireNotNull(repository.loadDocument(id))
        val oldFileRevision = fixture.dao.get(id)!!.fileRevision
        repository.saveReadingAnchor(id, text.contentRevision, ANCHOR)
        repository.download(id, fixture.scheduler.latest(id).token)
        val complete = requireNotNull(repository.loadDocument(id))
        assertEquals(SavedImageState.READY, complete.item.imageState)
        assertEquals("Image bytes are not a new text edition", text.contentRevision, complete.contentRevision)
        assertEquals(ANCHOR, complete.readingAnchor)
        assertNotEquals(oldFileRevision, fixture.dao.get(id)!!.fileRevision)
        val thumbnail = java.io.File(requireNotNull(complete.item.thumbnailPath))
        assertTrue(thumbnail.isFile)
        assertArrayEquals(java.util.Base64.getDecoder().decode(TINY_PNG), thumbnail.readBytes())
        assertTrue("The offline document references its retained image", complete.html.contains(thumbnail.toURI().toString()))
        val restarted = fixture.repository(Dispatchers.Unconfined)
        restarted.observeItems().first()
        assertEquals(complete, restarted.loadDocument(id))
        assertTrue("Recovery must retain image bytes referenced by the current document", thumbnail.isFile)
    }

    @Test fun missingRetainedImageOnReopenIsReportedHonestlyAndRetryRepairsItWithoutLosingText() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        fixture.downloader.image = { java.util.Base64.getDecoder().decode(TINY_PNG) }
        val id = repository.saveRssSnapshot(URL, "Illustrated article", "Ada", "Paper", "$HTML<img src=\"https://example.test/photo.png\">")
        repository.download(id, fixture.scheduler.latest(id).token)
        val complete = requireNotNull(repository.loadDocument(id))
        repository.saveReadingAnchor(id, complete.contentRevision, ANCHOR)
        assertTrue(java.io.File(requireNotNull(complete.item.thumbnailPath)).delete())
        val restarted = fixture.repository(Dispatchers.Unconfined)
        val missing = requireNotNull(restarted.loadDocument(id))
        assertEquals(SavedContentState.READY, missing.item.contentState)
        assertEquals(SavedImageState.FAILED, missing.item.imageState)
        assertTrue(missing.html.contains("A durable paragraph"))
        restarted.retry(id)
        restarted.download(id, fixture.scheduler.latest(id).token)
        val repaired = requireNotNull(restarted.loadDocument(id))
        assertEquals(SavedImageState.READY, repaired.item.imageState)
        assertEquals(complete.contentRevision, repaired.contentRevision)
        assertEquals(ANCHOR, repaired.readingAnchor)
        assertTrue(java.io.File(requireNotNull(repaired.item.thumbnailPath)).isFile)
    }

    @Test fun missingDurableBodyCannotRemainReadyWhenRetryExtractionFails() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val id = repository.saveRssSnapshot(URL, "Offline article", "Ada", "Paper", HTML)
        val row = fixture.dao.get(id)!!
        assertTrue(java.io.File(fixture.root, "$id/${row.fileRevision}/content.html").delete())
        assertNull(repository.loadDocument(id))
        assertEquals(SavedContentState.FAILED, repository.observeItem(id).first()!!.contentState)
        fixture.downloader.extract = { _, _ -> throw IOException("Still offline") }
        repository.retry(id)
        repository.download(id, fixture.scheduler.latest(id).token)
        assertNull(repository.loadDocument(id))
        assertEquals("A missing body and failed fetch must not be labelled offline-ready", SavedContentState.FAILED, repository.observeItem(id).first()!!.contentState)
    }

    @Test fun alreadyCancelledExtractionCannotStartANewFileCommitThroughAnUncontendedLock() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val id = repository.saveUrl(URL, "Original title")
        lateinit var operation: Job
        fixture.downloader.extract = { _, _ ->
            operation.cancel()
            // A download may finish at the same time its caller is cancelled, and
            // return without another suspension before the publication lock.
            article(HTML)
        }
        operation = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            repository.download(id, fixture.scheduler.latest(id).token)
        }
        operation.start()
        operation.join()
        val row = fixture.dao.get(id)!!
        assertTrue(operation.isCancelled)
        assertEquals(SavedContentState.QUEUED.name, row.contentState)
        assertEquals("Original title", row.title)
        assertNull(row.contentRevision)
        assertNull(row.fileRevision)
        assertTrue(fixture.root.walkTopDown().none { it.isFile })
    }

    @Test fun cancellationAfterSnapshotInsertCannotDeleteItsCommittedBodyOrSkipImageScheduling() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        lateinit var operation: Job
        var returned = false
        fixture.dao.afterInsert = { operation.cancel() }
        operation = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            repository.saveRssSnapshot(URL, "Snapshot", null, null, "$HTML<img src=\"https://example.test/photo.png\">")
            returned = true
        }
        operation.start()
        operation.join()
        val row = fixture.dao.all().single()
        assertTrue(operation.isCancelled)
        assertFalse("A completed critical section must still propagate caller cancellation", returned)
        assertTrue(java.io.File(fixture.root, "${row.id}/${row.fileRevision}/content.html").isFile)
        assertEquals(row.downloadToken, fixture.scheduler.latest(row.id).token)
        assertNotNull(repository.loadDocument(row.id))
    }

    @Test fun cancellationAfterUrlInsertCannotStrandTheCommittedDownloadToken() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        lateinit var operation: Job
        fixture.dao.afterInsert = { operation.cancel() }
        operation = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { repository.saveUrl(URL) }
        operation.start()
        operation.join()
        val row = fixture.dao.all().single()
        assertTrue(operation.isCancelled)
        assertEquals(row.downloadToken, fixture.scheduler.latest(row.id).token)
    }

    @Test fun cancellationAfterRetryUpdateStillSchedulesTheNewGeneration() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val id = repository.saveUrl(URL)
        val previous = fixture.scheduler.latest(id).token
        lateinit var operation: Job
        fixture.dao.afterUpdate = { if (it.downloadToken != previous) operation.cancel() }
        operation = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { repository.retry(id) }
        operation.start()
        operation.join()
        val row = fixture.dao.get(id)!!
        assertTrue(operation.isCancelled)
        assertNotEquals(previous, row.downloadToken)
        assertEquals(row.downloadToken, fixture.scheduler.latest(id).token)
        assertTrue(fixture.scheduler.latest(id).replace)
    }

    @Test fun cancellationAfterDownloadedTextUpdateCannotDiscardThePublishedRevision() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val id = repository.saveUrl(URL)
        lateinit var operation: Job
        fixture.dao.afterUpdate = { if (it.fileRevision != null) operation.cancel() }
        operation = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            repository.download(id, fixture.scheduler.latest(id).token)
        }
        operation.start()
        operation.join()
        val row = fixture.dao.get(id)!!
        assertTrue(operation.isCancelled)
        assertTrue(java.io.File(fixture.root, "$id/${row.fileRevision}/content.html").isFile)
        assertNotNull(repository.loadDocument(id))
    }

    @Test fun cancellationAfterImageRevisionUpdateCannotDiscardPublishedTextOrImages() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        fixture.downloader.image = { java.util.Base64.getDecoder().decode(TINY_PNG) }
        val id = repository.saveRssSnapshot(URL, "Snapshot", null, null, "$HTML<img src=\"https://example.test/photo.png\">")
        val before = repository.loadDocument(id)!!
        lateinit var operation: Job
        fixture.dao.afterUpdate = { if (it.thumbnailPath != null && it.downloadToken == null) operation.cancel() }
        operation = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            repository.download(id, fixture.scheduler.latest(id).token)
        }
        operation.start()
        operation.join()
        val row = fixture.dao.get(id)!!
        assertTrue(operation.isCancelled)
        assertTrue(java.io.File(fixture.root, "$id/${row.fileRevision}/content.html").isFile)
        assertTrue(java.io.File(row.thumbnailPath!!).isFile)
        val after = repository.loadDocument(id)!!
        assertEquals(before.contentRevision, after.contentRevision)
        assertEquals(SavedImageState.READY, after.item.imageState)
    }

    @Test fun cancellationAfterDeleteStillCancelsWorkAndRemovesOwnedFiles() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        val id = repository.saveRssSnapshot(URL, "Snapshot", null, null, HTML)
        lateinit var operation: Job
        fixture.dao.afterDelete = { operation.cancel() }
        operation = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { repository.remove(id) }
        operation.start()
        operation.join()
        assertTrue(operation.isCancelled)
        assertNull(fixture.dao.get(id))
        assertFalse(java.io.File(fixture.root, id).exists())
        assertTrue(id in fixture.scheduler.cancelled)
    }

    @Test fun failedSnapshotInsertStillRollsBackItsUnpublishedFiles() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        fixture.dao.beforeInsert = { throw IOException("Insert failed before commit") }
        val result = runCatching { repository.saveRssSnapshot(URL, "Snapshot", null, null, HTML) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(fixture.dao.all().isEmpty())
        assertTrue(fixture.root.listFiles().isNullOrEmpty())
        assertTrue(fixture.scheduler.requests.isEmpty())
    }

    @Test fun failedImageRevisionUpdateRetainsThePreviousBodyAndDiscardsUnpublishedFiles() = runRepositoryTest {
        val fixture = Fixture()
        val repository = fixture.repository(Dispatchers.Unconfined)
        fixture.downloader.image = { java.util.Base64.getDecoder().decode(TINY_PNG) }
        val id = repository.saveRssSnapshot(URL, "Snapshot", null, null, "$HTML<img src=\"https://example.test/photo.png\">")
        val before = repository.loadDocument(id)!!
        val previousRevision = fixture.dao.get(id)!!.fileRevision
        fixture.dao.beforeUpdate = {
            if (it.thumbnailPath != null && it.downloadToken == null) throw IOException("Update failed before commit")
        }
        repository.download(id, fixture.scheduler.latest(id).token)
        val after = repository.loadDocument(id)!!
        assertEquals(before.html, after.html)
        assertEquals(before.contentRevision, after.contentRevision)
        assertEquals(previousRevision, fixture.dao.get(id)!!.fileRevision)
        assertEquals(SavedImageState.FAILED, after.item.imageState)
        assertEquals(listOf("content.html"), java.io.File(fixture.root, id).walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    private fun runRepositoryTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { block() }
    }

    private inner class Fixture {
        val dao = MemoryDao()
        val root = temporary.newFolder()
        val downloader = ControlledDownloader()
        val scheduler = RecordingScheduler()
        val clock = AtomicLong(1_000L)
        fun repository(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = DefaultSavedLibraryRepository(
            dao, SavedLibraryFiles(root), downloader, scheduler, dispatcher, clock::incrementAndGet,
        )
    }

    private class ControlledDownloader : SavedArticleDownloader {
        var extract: suspend (String, String?) -> SavedExtractedArticle = { _, _ -> article(HTML) }
        var image: suspend (String) -> ByteArray = { throw IOException("No image fixture") }
        override suspend fun extract(url: String, titleHint: String?) = extract.invoke(url, titleHint)
        override suspend fun fetchImage(url: String) = image.invoke(url)
    }

    private class RecordingScheduler : SavedDownloadScheduler {
        data class Request(val id: String, val token: String, val replace: Boolean)
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val cancelled = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun enqueue(id: String, token: String, replace: Boolean) { requests += Request(id, token, replace) }
        override fun cancel(id: String) { cancelled += id }
        fun latest(id: String) = requests.last { it.id == id }
    }

    /** Mirrors Room IGNORE/UPDATE-only semantics; never an upsert that masks resurrection bugs. */
    private class MemoryDao : SavedLibraryDao {
        private val rows = MutableStateFlow<List<SavedLibraryEntity>>(emptyList())
        var beforeInsert: (() -> Unit)? = null
        var beforeUpdate: ((SavedLibraryEntity) -> Unit)? = null
        var afterInsert: (() -> Unit)? = null
        var afterUpdate: ((SavedLibraryEntity) -> Unit)? = null
        var afterDelete: (() -> Unit)? = null
        override fun observeItems(location: String?): Flow<List<SavedLibraryEntity>> = rows.map { items ->
            items.filter { location == null || it.location == location }
                .sortedWith(compareByDescending<SavedLibraryEntity> { it.savedAt }.thenByDescending { it.id })
        }
        override fun observeItem(id: String) = rows.map { items -> items.find { it.id == id } }
        override suspend fun get(id: String) = rows.value.find { it.id == id }
        override suspend fun findUrl(url: String) = rows.value.find { it.normalizedUrl == url }
        override suspend fun all() = rows.value.toList()
        override suspend fun insert(item: SavedLibraryEntity): Long {
            beforeInsert?.invoke()
            val result = synchronized(this) {
                if (rows.value.any { it.id == item.id || it.normalizedUrl == item.normalizedUrl }) -1L
                else { rows.value = rows.value + item; 1L }
            }
            // Model Room's commit followed by suspended-result dispatch. Cancellation
            // at this point cannot be treated as evidence that the write rolled back.
            afterInsert?.let { callback -> withContext(Dispatchers.Default) { callback() } }
            return result
        }
        override suspend fun update(item: SavedLibraryEntity): Int {
            beforeUpdate?.invoke(item)
            val result = synchronized(this) {
                if (rows.value.none { it.id == item.id }) 0
                else { rows.value = rows.value.map { if (it.id == item.id) item else it }; 1 }
            }
            afterUpdate?.let { callback -> withContext(Dispatchers.Default) { callback(item) } }
            return result
        }
        override suspend fun move(id: String, location: String) = mutate(id) { it.copy(location = location) }
        override suspend fun setRead(id: String, value: Boolean) = mutate(id) { it.copy(isRead = value) }
        override suspend fun saveAnchor(id: String, revision: String, anchor: String) = mutate(id) {
            if (it.contentRevision == revision) it.copy(readingAnchor = anchor) else it
        }
        override suspend fun delete(id: String) {
            synchronized(this) { rows.value = rows.value.filterNot { it.id == id } }
            afterDelete?.let { callback -> withContext(Dispatchers.Default) { callback() } }
        }
        private fun mutate(id: String, transform: (SavedLibraryEntity) -> SavedLibraryEntity) {
            synchronized(this) { rows.value = rows.value.map { if (it.id == id) transform(it) else it } }
        }
    }

    companion object {
        private const val TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII="
        private const val URL = "https://example.test/article"
        private const val HTML = "<article><h1>Offline article</h1><p>A durable paragraph, independent of the RSS cache.</p></article>"
        private const val ANCHOR = "{\"block\":1,\"offset\":12}"
        private fun article(html: String) = SavedExtractedArticle(html, "Downloaded article", "Ada", "Paper", "A durable paragraph")
    }
}
