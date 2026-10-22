package me.ash.reader.ui.page.home.reading

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EInkFormattingApplyTest {
    @Test(timeout = 10_000) fun workerCompletionReturnsToUiBeforeClosingPanel() {
        val uiThread = AtomicReference<Thread>()
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "formatting-ui").also(uiThread::set)
        }.asCoroutineDispatcher().use { ui ->
            val worker = Executors.newSingleThreadExecutor { Thread(it, "formatting-store") }
            try {
                val saves = AtomicInteger()
                val callbacks = mutableListOf<String>()
                val saved = Any()
                // Reproduce a caller that does not itself confine resumption.
                runBlocking(Dispatchers.Unconfined) {
                    applyEInkFormatting(
                        persist = {
                            saves.incrementAndGet()
                            suspendCancellableCoroutine<Any> { continuation ->
                                worker.execute { continuation.resume(saved) }
                            }
                        },
                        onSaved = {
                            assertSame(saved, it)
                            assertSame(uiThread.get(), Thread.currentThread())
                            callbacks += "close"
                        },
                        onSaveFailed = { throw AssertionError("Successful commit must not report save failure", it) },
                        onFinished = {
                            assertSame(uiThread.get(), Thread.currentThread())
                            callbacks += "finished"
                        },
                        uiDispatcher = ui,
                    )
                }
                assertEquals(1, saves.get())
                assertEquals(listOf("close", "finished"), callbacks)
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test(timeout = 10_000) fun failedSaveStaysOpenAndReportsFailureOnUi() =
        withUiDispatcher { ui, uiThread ->
            val failure = IOException("storage unavailable")
            val callbacks = mutableListOf<String>()
            applyEInkFormatting(
                persist = { throw failure },
                onSaved = { throw AssertionError("Failed persistence cannot close the panel") },
                onSaveFailed = {
                    assertSame(failure, it)
                    assertSame(uiThread.get(), Thread.currentThread())
                    callbacks += "error"
                },
                onFinished = {
                    assertSame(uiThread.get(), Thread.currentThread())
                    callbacks += "finished"
                },
                uiDispatcher = ui,
            )
            assertEquals(listOf("error", "finished"), callbacks)
        }

    @Test(timeout = 10_000) fun cancellationPropagatesWithoutFalseSaveError() =
        withUiDispatcher { ui, uiThread ->
            val cancellation = CancellationException("reader disposed")
            var finished = false
            try {
                applyEInkFormatting(
                    persist = { throw cancellation },
                    onSaved = { throw AssertionError("Cancelled save cannot close the panel") },
                    onSaveFailed = { throw AssertionError("Cancellation is not a storage error") },
                    onFinished = { assertSame(uiThread.get(), Thread.currentThread()); finished = true },
                    uiDispatcher = ui,
                )
                throw AssertionError("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertOriginalFailure(cancellation, actual)
            }
            assertTrue(finished)
        }

    @Test(timeout = 10_000) fun postCommitUiFailureIsNotReportedAsFailedPersistence() =
        withUiDispatcher { ui, uiThread ->
            val failure = IllegalStateException("UI failure after commit")
            var finished = false
            try {
                applyEInkFormatting(
                    persist = { "saved" },
                    onSaved = { assertSame(uiThread.get(), Thread.currentThread()); throw failure },
                    onSaveFailed = { throw AssertionError("The write already succeeded") },
                    onFinished = { assertSame(uiThread.get(), Thread.currentThread()); finished = true },
                    uiDispatcher = ui,
                )
                throw AssertionError("UI failure must propagate")
            } catch (actual: IllegalStateException) {
                assertOriginalFailure(failure, actual)
            }
            assertTrue(finished)
        }

    private fun assertOriginalFailure(expected: Throwable, actual: Throwable) {
        // Coroutine debug stack-trace recovery copies exceptions at dispatcher
        // boundaries while retaining the original as a cause. Check semantics
        // AND original identity, not a debug-mode-dependent top-level identity.
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        assertTrue("Original failure must be preserved", generateSequence(actual) { it.cause }.any { it === expected })
    }

    private fun withUiDispatcher(block: suspend (kotlinx.coroutines.CoroutineDispatcher, AtomicReference<Thread>) -> Unit) {
        val uiThread = AtomicReference<Thread>()
        // Debug mode decorates thread names; the actual executor Thread is the
        // invariant that matters for WebView confinement.
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "formatting-ui").also(uiThread::set)
        }.asCoroutineDispatcher().use { ui ->
            runBlocking(Dispatchers.Unconfined) { block(ui, uiThread) }
        }
    }
}
