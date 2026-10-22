package me.ash.reader.ui.page.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

@OptIn(ExperimentalCoroutinesApi::class)
class SaveRssToLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun snapshot(html: String? = "<p>Actual RSS article</p>") = RssLibrarySnapshot(
        "https://example.test/story", "Article title", "Author", "Publication", html,
    )

    @Test fun capturesExactVisibleContentWithoutUsingRssServices() = runTest(dispatcher) {
        val repo = FakeLibrary()
        val vm = SaveRssToLibraryViewModel(repo)
        vm.save(snapshot())
        advanceUntilIdle()
        assertEquals(listOf(snapshot()), repo.snapshots)
        assertTrue(repo.urls.isEmpty())
        assertTrue(vm.events.first().saved)
    }

    @Test fun absentOrBlankArticleTextQueuesUrlInsteadOfInventingOfflineContent() = runTest(dispatcher) {
        val repo = FakeLibrary()
        val vm = SaveRssToLibraryViewModel(repo)
        vm.save(snapshot(null))
        advanceUntilIdle()
        vm.save(snapshot(" \n "))
        advanceUntilIdle()
        assertEquals(listOf(snapshot().url, snapshot().url), repo.urls)
        assertTrue(repo.snapshots.isEmpty())
    }

    @Test fun duplicateTapDoesNotRaceAndSuccessWaitsForDurableCommit() = runTest(dispatcher) {
        val repo = FakeLibrary().apply { barrier = CompletableDeferred() }
        val vm = SaveRssToLibraryViewModel(repo)
        val events = mutableListOf<SaveRssLibraryResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect(events::add) }
        vm.save(snapshot())
        vm.save(snapshot())
        advanceUntilIdle()
        assertEquals(1, repo.snapshots.size)
        assertTrue(events.isEmpty())
        repo.barrier!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(SaveRssLibraryResult(true)), events)
    }

    @Test fun failureIsNotAcknowledgedAsSavedAndUserCanRetry() = runTest(dispatcher) {
        val repo = FakeLibrary().apply { error = IllegalStateException("disk unavailable") }
        val vm = SaveRssToLibraryViewModel(repo)
        vm.save(snapshot())
        advanceUntilIdle()
        assertFalse(vm.events.first().saved)
        repo.error = null
        vm.save(snapshot())
        advanceUntilIdle()
        assertTrue(vm.events.first().saved)
        assertEquals(2, repo.snapshots.size)
    }

    @Test fun cancellationIsNotReportedAsSuccessOrAnErrorToast() = runTest(dispatcher) {
        val repo = FakeLibrary().apply { error = CancellationException() }
        val vm = SaveRssToLibraryViewModel(repo)
        val events = mutableListOf<SaveRssLibraryResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect(events::add) }
        vm.save(snapshot())
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        repo.error = null
        vm.save(snapshot())
        advanceUntilIdle()
        assertEquals(listOf(SaveRssLibraryResult(true)), events)
    }

    private class FakeLibrary : SavedLibraryRepository {
        val snapshots = mutableListOf<RssLibrarySnapshot>()
        val urls = mutableListOf<String>()
        var barrier: CompletableDeferred<Unit>? = null
        var error: Exception? = null
        private suspend fun commit(): String {
            barrier?.await()
            error?.let { throw it }
            return "saved-id"
        }
        override suspend fun saveRssSnapshot(url: String, title: String, author: String?, sourceName: String?, html: String): String {
            snapshots += RssLibrarySnapshot(url, title, author, sourceName, html)
            return commit()
        }
        override suspend fun saveUrl(url: String, titleHint: String?): String {
            urls += url
            return commit()
        }
        override fun observeItems(location: SavedLocation?): Flow<List<SavedItem>> = emptyFlow()
        override fun observeItem(id: String): Flow<SavedItem?> = emptyFlow()
        override suspend fun loadDocument(id: String): SavedReadingDocument? = null
        override suspend fun move(id: String, location: SavedLocation) = error("Unexpected state mutation")
        override suspend fun setRead(id: String, value: Boolean) = error("Unexpected state mutation")
        override suspend fun saveReadingAnchor(id: String, contentRevision: String, anchorJson: String) = error("Unexpected state mutation")
        override suspend fun retry(id: String) = error("Unexpected state mutation")
        override suspend fun remove(id: String) = error("Unexpected state mutation")
    }
}
