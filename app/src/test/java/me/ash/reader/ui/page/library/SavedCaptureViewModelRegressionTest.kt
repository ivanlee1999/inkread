package me.ash.reader.ui.page.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.library.SavedItem
import me.ash.reader.domain.library.SavedLibraryRepository
import me.ash.reader.domain.library.SavedLocation
import me.ash.reader.domain.library.SavedReadingDocument
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real capture state machine, independent from the pure URL/routing policy tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedCaptureViewModelRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    @Test fun receivingOrCancellingAShareDoesNotSaveOrSubscribeAnything() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val model = model(repository)
        model.enqueue("A useful title\n$FIRST")
        val first = model.pending().single()
        assertEquals(FIRST, first.selectedUrl)
        assertEquals("A useful title", first.titleHint)
        assertEquals(LinkCaptureMode.CHOOSE.name, first.mode)
        runCurrent()
        assertTrue(repository.savedUrls.isEmpty())
        assertNull(model.savedItemId.value)
        model.dismiss(first.token)
        runCurrent()
        assertTrue(model.pending().isEmpty())
        assertTrue(repository.savedUrls.isEmpty())
    }

    @Test fun warmSharesAreQueuedAndStaleCallbacksCannotDismissANewerRequest() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val model = model(repository)
        model.enqueue(FIRST)
        model.enqueue(SECOND)
        val old = model.pending().first()
        val next = model.pending().last()
        assertNotEquals(old.token, next.token)
        model.dismiss(old.token)
        model.dismiss(old.token)
        model.saveSelected(old.token)
        assertNull(model.subscribe(old.token))
        runCurrent()
        assertEquals(listOf(next), model.pending())
        assertTrue(repository.savedUrls.isEmpty())
        model.dismiss(next.token)
        assertTrue(model.pending().isEmpty())
    }

    @Test fun multipleLinksRequireExplicitSelectionAndAChoiceOfSaveOrSubscribe() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val model = model(repository)
        model.enqueue("Read $FIRST or $SECOND", "Two articles")
        val token = model.pending().single().token
        assertNull(model.pending().single().selectedUrl)
        model.saveSelected(token)
        assertNull(model.subscribe(token))
        model.select(token, "https://example.test/not-shared")
        assertNull(model.pending().single().selectedUrl)
        runCurrent()
        assertTrue(repository.savedUrls.isEmpty())
        model.select(token, SECOND)
        assertEquals(SECOND, model.subscribe(token))
        assertTrue(model.pending().isEmpty())
        assertTrue("Subscribe must never silently create a Saved item", repository.savedUrls.isEmpty())
    }

    @Test fun repeatedSaveClicksCommitOnceAndDoNotDismissTheNextQueuedShare() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val completion = CompletableDeferred<String>()
        repository.save = { _, _ -> completion.await() }
        val model = model(repository)
        model.enqueue(FIRST)
        model.enqueue(SECOND)
        val first = model.pending().first()
        model.saveSelected(first.token)
        repeat(6) { model.saveSelected(first.token); model.dismiss(first.token) }
        runCurrent()
        assertEquals(listOf(FIRST), repository.savedUrls)
        assertTrue(model.pending().first().saving)
        assertEquals(2, model.pending().size)
        completion.complete("saved-first")
        runCurrent()
        assertEquals(SECOND, model.pending().single().selectedUrl)
        assertEquals("saved-first", model.savedItemId.value)
        model.dismiss(first.token)
        assertEquals(SECOND, model.pending().single().selectedUrl)
        model.acknowledgeSaved()
        assertNull(model.savedItemId.value)
    }

    @Test fun recreationRestoresQueuedSelectionWithoutAnUnrequestedSave() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val state = SavedStateHandle()
        val first = model(repository, state)
        first.enqueue("$FIRST $SECOND")
        val token = first.pending().single().token
        first.select(token, SECOND)
        first.enqueue(FIRST, "Later request")
        val restored = model(repository, state.recreated())
        runCurrent()
        assertEquals(first.pending(), restored.pending())
        assertEquals(SECOND, restored.pending().first().selectedUrl)
        assertTrue(repository.savedUrls.isEmpty())
    }

    @Test fun interruptedCommittedIntentReplaysIdempotentlyAfterProcessStateRestoration() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val state = SavedStateHandle()
        val suspended = CompletableDeferred<String>()
        repository.save = { _, _ -> suspended.await() }
        val first = model(repository, state)
        first.enqueue(FIRST, "From another app")
        first.saveSelected(first.pending().single().token)
        runCurrent()
        assertEquals(listOf(FIRST), repository.savedUrls)
        val persisted = state.recreated()
        stores.last().clear()
        repository.save = { _, _ -> "one-durable-item" }
        val restarted = model(repository, persisted)
        runCurrent()
        assertEquals("A chosen in-flight save replays the same URL, not a different request", listOf(FIRST, FIRST), repository.savedUrls)
        assertTrue(restarted.pending().isEmpty())
        assertEquals("one-durable-item", restarted.savedItemId.value)
    }

    @Test fun storageFailurePreservesTheChosenLinkAndAllowsAnExplicitRetry() = runTest(dispatcher) {
        val repository = CaptureRepository()
        repository.save = { _, _ -> throw IOException("Deterministic disk failure") }
        val model = model(repository)
        model.enqueue(FIRST)
        val token = model.pending().single().token
        model.saveSelected(token)
        runCurrent()
        assertFalse(model.pending().single().saving)
        assertEquals(token, model.pending().single().token)
        assertEquals(FIRST, model.pending().single().selectedUrl)
        assertFalse(model.error.value.isNullOrBlank())
        assertNull(model.savedItemId.value)
        repository.save = { _, _ -> "retried-item" }
        model.saveSelected(token)
        runCurrent()
        assertTrue(model.pending().isEmpty())
        assertNull(model.error.value)
        assertEquals("retried-item", model.savedItemId.value)
    }

    @Test fun invalidShareCannotTriggerSaveAndOversizedShareDoesNotDestroyExistingQueue() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val model = model(repository)
        model.enqueue("Not a link")
        val invalid = model.pending().single()
        model.saveSelected(invalid.token)
        assertNull(model.subscribe(invalid.token))
        model.enqueue("x".repeat(32_769))
        runCurrent()
        assertEquals(listOf(invalid), model.pending())
        assertFalse(model.error.value.isNullOrBlank())
        assertTrue(repository.savedUrls.isEmpty())
        model.dismiss(invalid.token)
        assertNull(model.error.value)
    }

    @Test fun queueLimitsPreserveExistingRequestsResultsAndBoundTheTitleHint() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val model = model(repository)
        model.enqueue(FIRST)
        model.saveSelected(model.pending().single().token)
        runCurrent()
        assertEquals("saved-item", model.savedItemId.value)
        model.enqueue(SECOND, "T".repeat(10_000))
        assertEquals(512, model.pending().single().titleHint!!.length)
        repeat(7) { model.enqueue("https://example.test/queued-$it") }
        val existing = model.pending()
        assertEquals(8, existing.size)
        repeat(10) { model.enqueue("https://example.test/excess-$it") }
        runCurrent()
        assertEquals("Overflow never silently drops an existing request", existing, model.pending())
        assertEquals("saved-item", model.savedItemId.value)
        assertFalse(model.error.value.isNullOrBlank())
        assertEquals(listOf(FIRST), repository.savedUrls)
    }

    @Test fun encodedQueueBudgetIsBoundedEvenWhenEachIndividualShareFits() = runTest(dispatcher) {
        val repository = CaptureRepository()
        val model = model(repository)
        val text = "$FIRST " + "x".repeat(32_000)
        repeat(8) { model.enqueue(text, "Large share $it") }
        assertTrue(model.queue.value.length <= 128 * 1024)
        assertTrue(model.pending().size in 1..7)
        assertFalse(model.error.value.isNullOrBlank())
        assertTrue(repository.savedUrls.isEmpty())
    }

    private fun model(repository: SavedLibraryRepository, state: SavedStateHandle = SavedStateHandle()) =
        SavedCaptureViewModel(repository, state).also { model ->
            stores += ViewModelStore().also { it.put("capture", model) }
        }

    private fun SavedStateHandle.recreated(): SavedStateHandle =
        SavedStateHandle(keys().associateWith { get<Any?>(it) })

    /** Deliberately fails unused mutations instead of letting a wrong route pass silently. */
    private class CaptureRepository : SavedLibraryRepository {
        val savedUrls = mutableListOf<String>()
        var save: suspend (String, String?) -> String = { _, _ -> "saved-item" }
        override suspend fun saveUrl(url: String, titleHint: String?): String {
            savedUrls += url
            return save(url, titleHint)
        }
        override fun observeItems(location: SavedLocation?): Flow<List<SavedItem>> = error("Capture does not query library lists")
        override fun observeItem(id: String): Flow<SavedItem?> = error("Capture does not load a reader")
        override suspend fun saveRssSnapshot(url: String, title: String, author: String?, sourceName: String?, html: String): String = error("A shared link is not an RSS snapshot")
        override suspend fun loadDocument(id: String): SavedReadingDocument? = error("Capture does not read documents")
        override suspend fun move(id: String, location: SavedLocation) = error("Capture cannot move existing items")
        override suspend fun setRead(id: String, value: Boolean) = error("Capture cannot mark existing items")
        override suspend fun saveReadingAnchor(id: String, contentRevision: String, anchorJson: String) = error("Capture cannot change reader progress")
        override suspend fun retry(id: String) = error("Capture cannot retry an unrelated download")
        override suspend fun remove(id: String) = error("Capture cannot remove an item")
    }

    companion object {
        private const val FIRST = "https://example.test/first"
        private const val SECOND = "https://example.test/second"
    }
}
