package me.ash.reader.ui.page.home.reading

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
        Executors.newSingleThreadExecutor { Thread(it, "formatting-ui") }.asCoroutineDispatcher().use { ui ->
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
                            assertEquals("formatting-ui", Thread.currentThread().name)
                            callbacks += "close"
                        },
                        onSaveFailed = { throw AssertionError("Successful commit must not report save failure", it) },
                        onFinished = {
                            assertEquals("formatting-ui", Thread.currentThread().name)
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
        withUiDispatcher { ui ->
            val failure = IOException("storage unavailable")
            val callbacks = mutableListOf<String>()
            applyEInkFormatting(
                persist = { throw failure },
                onSaved = { throw AssertionError("Failed persistence cannot close the panel") },
                onSaveFailed = {
                    assertSame(failure, it)
                    assertEquals("formatting-ui", Thread.currentThread().name)
                    callbacks += "error"
                },
                onFinished = {
                    assertEquals("formatting-ui", Thread.currentThread().name)
                    callbacks += "finished"
                },
                uiDispatcher = ui,
            )
            assertEquals(listOf("error", "finished"), callbacks)
        }

    @Test(timeout = 10_000) fun cancellationPropagatesWithoutFalseSaveError() =
        withUiDispatcher { ui ->
            val cancellation = CancellationException("reader disposed")
            var finished = false
            try {
                applyEInkFormatting(
                    persist = { throw cancellation },
                    onSaved = { throw AssertionError("Cancelled save cannot close the panel") },
                    onSaveFailed = { throw AssertionError("Cancellation is not a storage error") },
                    onFinished = { finished = true },
                    uiDispatcher = ui,
                )
                throw AssertionError("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
            assertTrue(finished)
        }

    @Test(timeout = 10_000) fun postCommitUiFailureIsNotReportedAsFailedPersistence() =
        withUiDispatcher { ui ->
            val failure = IllegalStateException("UI failure after commit")
            var finished = false
            try {
                applyEInkFormatting(
                    persist = { "saved" },
                    onSaved = { throw failure },
                    onSaveFailed = { throw AssertionError("The write already succeeded") },
                    onFinished = { finished = true },
                    uiDispatcher = ui,
                )
                throw AssertionError("UI failure must propagate")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            assertTrue(finished)
        }

    private fun withUiDispatcher(block: suspend (kotlinx.coroutines.CoroutineDispatcher) -> Unit) {
        Executors.newSingleThreadExecutor { Thread(it, "formatting-ui") }.asCoroutineDispatcher().use { ui ->
            runBlocking(Dispatchers.Unconfined) { block(ui) }
        }
    }
}
