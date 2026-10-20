package me.ash.reader.ui.page.home.reading

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EInkDocumentOwnershipTest {
    @Test fun `already cancelled producer does not start an HTML build`() = runBlocking {
        var built = false
        val job = launch(Dispatchers.Default) {
            coroutineContext.cancel()
            withEInkDocument(build = { built = true; "output" }, dispose = {}, use = {})
        }
        job.join()
        assertFalse(built)
    }

    @Test fun `cancel while builder owns output disposes file after dispatcher handoff`() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        var used = false
        val job = launch(Dispatchers.Default) {
            withEInkDocument(
                build = { started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); "output" },
                dispose = { assertEquals("output", it); disposed.countDown() },
                use = { used = true },
            )
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        finish.countDown()
        job.join()
        assertFalse(used)
        assertEquals(0, disposed.count)
    }

    @Test fun `successful use and failed use both dispose exactly once`() = runBlocking {
        var disposed = 0
        withEInkDocument(build = { "output" }, dispose = { disposed++ }, use = {})
        try {
            withEInkDocument(build = { "output" }, dispose = { disposed++ }, use = { error("failed") })
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(2, disposed)
    }
}
