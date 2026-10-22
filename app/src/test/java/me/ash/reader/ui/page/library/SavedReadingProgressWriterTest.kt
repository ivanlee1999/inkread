package me.ash.reader.ui.page.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SavedReadingProgressWriterTest {
    @Test fun rapidTurnsAreBoundedAndExitWaitsForTheLatestAcceptedAnchor() = runTest {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val writer = SavedReadingProgressWriter(this, StandardTestDispatcher(testScheduler),
            save = { if (it == "first") release.await(); writes += it }, onError = {})
        writer.submit("first")
        runCurrent()
        repeat(10_000) { writer.submit("position-$it") }
        val exit = async { writer.drain() }
        runCurrent()
        assertFalse("Back must retain its reader owner until disk completion", exit.isCompleted)
        release.complete(Unit)
        writer.close()
        advanceUntilIdle()
        assertTrue(exit.await())
        assertEquals(listOf("first", "position-9999"), writes)
    }

    @Test fun closingNavigationScopeDoesNotCancelApplicationOwnedFinalWrite() = runTest {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val writer = SavedReadingProgressWriter(this, StandardTestDispatcher(testScheduler),
            save = { release.await(); writes += it }, onError = {})
        val caller = launch { writer.submit("before-exit") }
        runCurrent()
        caller.cancel()
        writer.submit("final-export")
        writer.close()
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(writer.drain())
        assertEquals("final-export", writes.last())
    }

    @Test fun diskFailureIsReportedAndTheSameAnchorCanBeRetried() = runTest {
        var failing = true
        val errors = mutableListOf<Boolean>()
        val writes = mutableListOf<String>()
        val writer = SavedReadingProgressWriter(this, StandardTestDispatcher(testScheduler),
            save = { if (failing) error("synthetic disk failure"); writes += it }, onError = { errors += it })
        writer.submit("latest")
        runCurrent()
        assertFalse(writer.drain())
        failing = false
        writer.submit("latest")
        runCurrent()
        assertTrue(writer.drain())
        writer.close()
        advanceUntilIdle()
        assertEquals(listOf(true, false), errors)
        assertEquals(listOf("latest"), writes)
    }

    @Test fun duplicateLifecycleAndExitExportsDoNotRewriteTheSamePosition() = runTest {
        val writes = mutableListOf<String>()
        val writer = SavedReadingProgressWriter(this, StandardTestDispatcher(testScheduler), save = { writes += it }, onError = {})
        writer.submit("same-canonical-position")
        runCurrent()
        writer.submit("same-canonical-position")
        writer.close()
        advanceUntilIdle()
        assertTrue(writer.drain())
        assertEquals(listOf("same-canonical-position"), writes)
    }
    @Test fun disposalCallbacksCannotQueueAfterTheWriterHasClosed() = runTest {
        val writes = mutableListOf<String>()
        val writer = SavedReadingProgressWriter(this, StandardTestDispatcher(testScheduler), save = { writes += it }, onError = {})
        assertTrue(writer.submit("last-accepted"))
        writer.close()
        assertFalse(writer.submit("stale-disposal"))
        advanceUntilIdle()
        assertTrue(writer.drain())
        assertEquals(listOf("last-accepted"), writes)
    }

}
