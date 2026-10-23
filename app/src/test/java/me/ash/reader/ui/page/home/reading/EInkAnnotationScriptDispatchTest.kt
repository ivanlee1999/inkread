package me.ash.reader.ui.page.home.reading

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class EInkAnnotationScriptDispatchTest {
    private class OwnerQueue {
        val owner = Thread.currentThread()
        val queue = mutableListOf<() -> Unit>()
        val gate = EInkAutoHighlightGesture()
        var document = "first"
        var attached = true
        var executed = 0
        val dispatch = EInkAnnotationScriptDispatch(
            isOwnerThread = { Thread.currentThread() === owner },
            postToOwner = { command -> synchronized(queue) { queue.add(command) } },
            currentDocument = { document },
            currentRevision = { gate.currentRevision },
            currentSessionRevision = { gate.currentSessionRevision },
            isAttached = { attached },
        )
        fun command(revision: Long = gate.currentRevision) = dispatch.dispatch(document, revision) {
            assertSame(owner, Thread.currentThread())
            executed++
        }
        fun drain() {
            val commands = synchronized(queue) { queue.toList().also { queue.clear() } }
            commands.forEach { it() }
        }
        fun worker(action: () -> Unit) {
            val failure = AtomicReference<Throwable?>()
            val thread = Thread({ try { action() } catch (error: Throwable) { failure.set(error) } }, "layout-worker")
            thread.start()
            thread.join(5_000)
            assertFalse("Worker must not wait for the owner queue", thread.isAlive)
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        }
    }

    @Test fun ownerCommandsStaySynchronous() {
        val owner = OwnerQueue()
        owner.command()
        assertEquals(1, owner.executed)
        assertTrue(owner.queue.isEmpty())
    }

    @Test fun workerCleanupRunsOnlyWhenOwnerServicesQueue() {
        val owner = OwnerQueue()
        owner.worker { owner.command(owner.gate.cancelGesture()) }
        assertEquals(0, owner.executed)
        owner.drain()
        assertEquals(1, owner.executed)
    }

    @Test fun resizeRevokesQueuedBridgeResultBeforeOwnerCleanup() {
        val owner = OwnerQueue()
        val token = requireNotNull(owner.gate.down(true, false))
        owner.gate.selectionStarted()
        owner.gate.up()
        owner.worker { owner.command(owner.gate.cancelGesture()) }
        assertEquals(0, owner.executed)
        assertFalse("Already-queued bridge result cannot save while cleanup waits", owner.gate.consume(token))
        owner.drain()
        assertEquals(1, owner.executed)
    }

    @Test fun staleDocumentCleanupCannotReachReplacementDocument() {
        val owner = OwnerQueue()
        owner.worker { owner.command(owner.gate.cancelGesture()) }
        owner.document = "replacement"
        owner.drain()
        assertEquals(0, owner.executed)
    }

    @Test fun queuedCancellationCannotCancelNewNativeGesture() {
        val owner = OwnerQueue()
        owner.worker { owner.command(owner.gate.cancelGesture()) }
        val fresh = requireNotNull(owner.gate.down(true, false))
        owner.gate.selectionStarted()
        owner.gate.up()
        owner.drain()
        assertEquals(0, owner.executed)
        assertTrue(owner.gate.consume(fresh))
    }

    @Test fun cancellationTicketRemainsStaleEvenIfEnqueuedAfterNewDown() {
        val owner = OwnerQueue()
        val oldRevision = owner.gate.cancelGesture()
        owner.gate.down(true, false)
        owner.worker { owner.command(oldRevision) }
        owner.drain()
        assertEquals(0, owner.executed)
    }

    @Test fun onlyLatestRepeatedCancellationRemainsEligible() {
        val owner = OwnerQueue()
        owner.worker {
            owner.command(owner.gate.cancelGesture())
            owner.command(owner.gate.cancelGesture())
        }
        owner.drain()
        assertEquals(1, owner.executed)
    }

    @Test fun workerInvalidationCancelsTouchUpAndNextDownRecovers() {
        val owner = OwnerQueue()
        val touch = EInkReaderTouchArbitrator(8f, 56f, 500L)
        touch.down(80f, 50f, 0L, 100f, 100f, true, false, 0f, 0f)
        owner.worker { touch.cancel() }
        assertFalse(touch.permitsNativeSelection)
        val cancelled = touch.up(80f, 50f, 100L)
        assertNull(cancelled.action)
        assertEquals(EInkReaderTouchArbitrator.Dispatch.CONSUME, cancelled.dispatch)
        touch.down(80f, 50f, 200L, 100f, 100f, true, false, 0f, 0f)
        assertTrue(touch.permitsNativeSelection)
        assertEquals(EInkReaderTouchArbitrator.Action.NEXT_PAGE, touch.up(80f, 50f, 300L).action)
    }

    @Test fun detachedReaderDoesNotEvaluatePendingScript() {
        val owner = OwnerQueue()
        owner.worker { owner.command(owner.gate.cancelGesture()) }
        owner.attached = false
        owner.drain()
        assertEquals(0, owner.executed)
    }

    @Test fun concurrentCancellationCannotEraseSessionEndReset() {
        val owner = OwnerQueue()
        val token = requireNotNull(owner.gate.down(true, false))
        owner.gate.selectionStarted()
        owner.gate.up()
        assertTrue(owner.gate.consume(token))
        val endedSession = owner.gate.finishSession()
        owner.worker { owner.command(owner.gate.cancelGesture()) }
        var reset = false
        owner.dispatch.dispatch(owner.document, endedSession, sessionReset = true) { reset = true }
        assertTrue("JS must clear its consumed latch even if cancellation advanced", reset)
        owner.drain()
        val fresh = requireNotNull(owner.gate.down(true, false))
        owner.gate.selectionStarted()
        owner.gate.up()
        assertTrue(owner.gate.consume(fresh))
    }

    @Test fun queuedOldSessionEndCannotClearANewerDown() {
        val owner = OwnerQueue()
        val endedSession = owner.gate.finishSession()
        var reset = false
        owner.worker {
            owner.dispatch.dispatch(owner.document, endedSession, sessionReset = true) { reset = true }
        }
        owner.gate.down(true, false)
        owner.drain()
        assertFalse(reset)
    }

    @Test fun sessionEndInvalidatesQueuedCompletionEvenAfterReattach() {
        val owner = OwnerQueue()
        owner.worker { owner.command() }
        owner.gate.finishSession()
        owner.drain()
        assertEquals(0, owner.executed)
    }
}
