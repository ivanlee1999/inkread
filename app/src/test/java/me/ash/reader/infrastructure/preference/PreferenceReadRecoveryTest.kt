package me.ash.reader.infrastructure.preference

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceReadRecoveryTest {
    @Test fun realSuccessThenTransientIoFailureResumesWithUpdatedValue() = runBlocking {
        var subscriptions = 0
        var error: Throwable? = IOException("Prior failure")
        val waits = mutableListOf<Long>()
        val reportedErrors = mutableListOf<Throwable>()
        val transientFailure = IOException("Transient")
        val values = flow {
            subscriptions++
            if (subscriptions == 1) {
                emit(12)
                throw transientFailure
            }
            emit(24)
        }.recoverPreferenceReadErrors(
            onRead = { error = null },
            onFailure = { error = it; reportedErrors += it },
            waitBeforeRetry = {
                assertSame("A transient error must be visible during retry", transientFailure, error)
                waits += it
            },
        ).toList()

        assertEquals(listOf(transientFailure), reportedErrors)
        assertEquals(listOf(12, 24), values)
        assertEquals(listOf(250L), waits)
        assertEquals(2, subscriptions)
        assertNull(error)
    }

    @Test fun persistentFailureKeepsLastGoodSnapshotAndManualRetryClearsErrorOnlyOnEmission() = runBlocking {
        val retry = MutableStateFlow(0)
        val observed = Channel<Int>(Channel.UNLIMITED)
        val failures = Channel<Throwable>(Channel.UNLIMITED)
        val retryStarted = Channel<Unit>(Channel.UNLIMITED)
        val nextRead = Channel<Int>(Channel.UNLIMITED)
        val waits = mutableListOf<Long>()
        val diskFailure = IOException("Disk unavailable")
        var subscriptions = 0
        var lastGood: Int? = null
        var error: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            retry.flatMapLatest { attempt ->
                flow {
                    if (attempt == 0) {
                        subscriptions++
                        if (subscriptions == 1) emit(12)
                        throw diskFailure
                    }
                    retryStarted.send(Unit)
                    emit(nextRead.receive())
                }.recoverPreferenceReadErrors(
                    onRead = { error = null },
                    onFailure = { error = it; failures.trySend(it) },
                    waitBeforeRetry = { waits += it },
                )
            }.collect {
                lastGood = it
                observed.send(it)
            }
        }
        try {
            assertEquals(12, observed.receive())
            repeat(4) { assertSame(diskFailure, failures.receive()) }
            assertEquals(4, subscriptions)
            assertEquals(listOf(250L, 500L, 1_000L), waits)
            assertEquals(12, lastGood)
            assertSame(diskFailure, error)

            retry.value++
            retryStarted.receive()
            assertEquals(12, lastGood)
            assertSame("Clicking Retry must not pretend that reading succeeded", diskFailure, error)

            nextRead.send(31)
            assertEquals(31, observed.receive())
            assertEquals(31, lastGood)
            assertNull(error)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun initialPersistentFailureNeverEmitsDefaults() = runBlocking {
        var subscriptions = 0
        val waits = mutableListOf<Long>()
        var error: Throwable? = null
        val failure = IOException("Cannot open preferences")
        val values = flow<Int> {
            subscriptions++
            throw failure
        }.recoverPreferenceReadErrors(
            onRead = { error = null },
            onFailure = { error = it },
            waitBeforeRetry = { waits += it },
        ).toList()
        assertTrue(values.isEmpty())
        assertEquals(4, subscriptions)
        assertEquals(listOf(250L, 500L, 1_000L), waits)
        assertSame(failure, error)
    }

    @Test fun nonIoFailureIsReportedWithoutAutomaticRetry() = runBlocking {
        val failure = IllegalStateException("Unexpected failure")
        var subscriptions = 0
        var error: Throwable? = null
        val waits = mutableListOf<Long>()
        val values = flow<Int> {
            subscriptions++
            emit(12)
            throw failure
        }.recoverPreferenceReadErrors(
            onRead = { error = null },
            onFailure = { error = it },
            waitBeforeRetry = { waits += it },
        ).toList()
        assertEquals(listOf(12), values)
        assertEquals(1, subscriptions)
        assertTrue(waits.isEmpty())
        assertSame(failure, error)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingVisibleReadFailure() = runBlocking {
        val cancellation = CancellationException("Reader stopped")
        var reportedFailure: Throwable? = null
        val waits = mutableListOf<Long>()
        var caught: Throwable? = null
        try {
            flow<Int> { throw cancellation }.recoverPreferenceReadErrors(
                onRead = {},
                onFailure = { reportedFailure = it },
                waitBeforeRetry = { waits += it },
            ).toList()
        } catch (cause: CancellationException) {
            caught = cause
        }
        assertSame(cancellation, caught)
        assertNull(reportedFailure)
        assertTrue(waits.isEmpty())
    }
}
