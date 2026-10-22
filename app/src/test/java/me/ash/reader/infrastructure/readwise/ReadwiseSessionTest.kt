package me.ash.reader.infrastructure.readwise

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.annotation.*
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Synthetic transport plus a fenced outbox fake. No Android runtime or network is used. */
class ReadwiseSessionTest {
    @Test fun ordinaryOfflineFailureRetriesCreateWhenConnectionReturns() = runBlocking {
        var offline = true
        harness { request ->
            if (offline) {
                request.tag(ReadwiseDeliveryTrace::class.java)!!.listenerObserved = true
                throw IOException("synthetic pre-send network failure")
            }
            created(request)
        }.use { h ->
            h.connect(); h.queue.add("a")
            h.session.drain()
            assertFalse(h.queue.rows.single().reconcile)
            assertNotNull(h.queue.connection.retryAt)
            offline = false; h.now += 31_000
            h.session.drain()
            assertEquals(listOf("a"), h.queue.acknowledged.map { it.first })
            assertEquals(listOf("POST", "POST"), h.exportRequests().map { it.method })
        }
    }

    @Test fun unauthorizedPausesWholeConnectionAndKeepsAnnotations() = runBlocking {
        harness { response(it, 401) }.use { h ->
            h.connect(); h.queue.add("a"); h.queue.add("b")
            h.session.drain()
            assertFalse(h.queue.connection.exportEnabled)
            assertFalse(h.session.state.value.exportEnabled)
            assertTrue(h.session.state.value.error!!.contains("rejected"))
            assertEquals(2, h.queue.rows.size)
            assertEquals(1, h.exportRequests().size)
            h.session.drain(); assertEquals(1, h.exportRequests().size)
        }
    }

    @Test fun retryAfterIsConnectionWideAndSurvivesAnotherDrain() = runBlocking {
        var limited = true
        harness { if (limited) response(it, 429).newBuilder().header("Retry-After", "120").build() else created(it) }.use { h ->
            h.connect(); h.queue.add("a"); h.queue.add("b")
            h.session.drain(); h.session.drain()
            assertEquals(1, h.exportRequests().size)
            assertEquals(h.now + 120_000, h.queue.connection.retryAt)
            assertTrue(h.scheduler.delays.contains(120_000))
            h.now += 119_999; h.session.drain(); assertEquals(1, h.exportRequests().size)
            h.now += 1; limited = false; h.session.drain()
            assertEquals(2, h.queue.acknowledged.size)
        }
    }

    @Test fun lostAcknowledgementOnlyUsesReadOnlyReconciliation() = runBlocking {
        harness { request ->
            if (request.method == "POST") throw IOException("synthetic lost acknowledgement")
            response(request, 200, """{"nextPageCursor":null,"results":[{"title":"Title","author":"Author","source_url":"https://example.test/story","highlights":[{"id":44,"text":"quote","note":"original"}]}]}""")
        }.use { h ->
            h.connect(); h.queue.add("a")
            h.session.drain()
            assertTrue(h.queue.rows.single().reconcile)
            assertEquals("original", h.queue.rows.single().payload.note)
            h.now += 31_000; h.session.drain()
            assertEquals(listOf("POST", "GET"), h.exportRequests().map { it.method })
            assertEquals("44", h.queue.acknowledged.single().second)
        }
    }

    @Test fun postSuccessThen401KeepsReadOnlyReconciliationAcrossResume() = runBlocking {
        var reject = true
        harness { request ->
            if (request.method == "POST") response(request, 200, """[{"modified_highlights":[]}]""")
            else if (reject) response(request, 401)
            else response(request, 200, """{"nextPageCursor":null,"results":[{"title":"Title","author":"Author","source_url":"https://example.test/story","highlights":[{"id":44,"text":"quote","note":"original"}]}]}""")
        }.use { h ->
            h.connect(); h.queue.add("a"); h.session.drain()
            assertFalse(h.session.state.value.exportEnabled)
            assertTrue(h.queue.rows.single().reconcile)
            reject = false; h.session.setExportEnabled(true); h.session.drain()
            assertEquals(listOf("POST", "GET", "GET"), h.exportRequests().map { it.method })
            assertEquals(1, h.queue.acknowledged.size)
        }
    }

    @Test fun dedupThen429PersistsUncertainFlagInsteadOfResendingCreate() = runBlocking {
        var limited = true
        harness { request ->
            if (request.method == "POST") response(request, 200, """[{"modified_highlights":[]}]""")
            else if (limited) response(request, 429).newBuilder().header("Retry-After", "90").build()
            else response(request, 200, """{"nextPageCursor":null,"results":[{"title":"Title","author":"Author","source_url":"https://example.test/story","highlights":[{"id":44,"text":"quote","note":"original"}]}]}""")
        }.use { h ->
            h.connect(); h.queue.add("a"); h.session.drain()
            assertTrue(h.queue.rows.single().reconcile)
            limited = false; h.now += 90_000; h.session.drain()
            assertEquals(listOf("POST", "GET", "GET"), h.exportRequests().map { it.method })
            assertEquals(1, h.queue.acknowledged.size)
        }
    }

    @Test fun unresolvedUncertainCreateRequiresReviewWithoutPostReplay() = runBlocking {
        harness { request -> if (request.method == "POST") throw IOException("lost ack") else response(request, 200, """{"nextPageCursor":null,"results":[]}""") }.use { h ->
            h.connect(); h.queue.add("a"); h.session.drain(); h.now += 31_000; h.session.drain()
            assertEquals(listOf("POST", "GET"), h.exportRequests().map { it.method })
            assertTrue(h.queue.rows.single().review)
            h.session.retryAnnotations(listOf("a")); h.session.drain()
            assertEquals(2, h.exportRequests().size)
        }
    }

    @Test fun noteEditedDuringCreateUsesFrozenCreateThenPatchLatestNote() = runBlocking {
        lateinit var h: Harness
        h = harness { request ->
            if (request.method == "POST") h.queue.edit("a", "new note")
            created(request)
        }
        h.use {
            h.connect(); h.queue.add("a"); h.session.drain()
            val requests = h.exportRequests()
            assertEquals(listOf("POST", "PATCH"), requests.map { it.method })
            assertTrue(requests[0].bodyText().contains("original"))
            assertFalse(requests[0].bodyText().contains("new note"))
            assertTrue(requests[1].bodyText().contains("new note"))
            assertEquals(2, h.queue.acknowledged.size)
        }
    }

    @Test fun disconnectDuringCallCancelsAndRejectsLateAcknowledgement() = runBlocking {
        lateinit var h: Harness
        h = harness { request -> runBlocking { h.session.disconnect() }; created(request) }
        h.use {
            h.connect(); h.queue.add("a"); val oldEpoch = h.queue.connection.epoch
            h.session.drain()
            assertFalse(h.session.state.value.connected)
            assertTrue(h.queue.connection.epoch > oldEpoch)
            assertNull(h.credentials.value)
            assertTrue(h.queue.acknowledged.isEmpty())
            h.session.drain(); assertEquals(1, h.exportRequests().size)
        }
    }

    @Test fun rapidPauseResumeCannotReplayAnUncertainCancelledCreate() = runBlocking {
        lateinit var h: Harness
        h = harness { request ->
            if (request.method == "POST") {
                runBlocking { h.session.setExportEnabled(false); h.session.setExportEnabled(true) }
                created(request)
            } else response(request, 200, """{"nextPageCursor":null,"results":[{"title":"Title","author":"Author","source_url":"https://example.test/story","highlights":[{"id":44,"text":"quote","note":"original"}]}]}""")
        }
        h.use {
            h.connect(); h.queue.add("a"); h.session.drain(); h.session.drain()
            assertEquals(listOf("POST", "GET"), h.exportRequests().map { it.method })
            assertEquals(1, h.queue.acknowledged.size)
        }
    }

    @Test fun replacementCannotReplayOldBacklogOrBindOldRemoteId() = runBlocking {
        lateinit var h: Harness
        var replace = true
        h = harness { request ->
            if (replace) { replace = false; runBlocking { h.session.connect("replacement-fixture", true) } }
            created(request)
        }
        h.use {
            h.connect(); h.queue.add("old"); h.session.drain()
            assertTrue(h.queue.acknowledged.isEmpty())
            assertEquals(1, h.exportRequests().size)
            h.queue.add("new"); h.session.drain()
            assertEquals(listOf("new"), h.queue.acknowledged.map { it.first })
            assertEquals("Token replacement-fixture", h.exportRequests().last().header("Authorization"))
        }
    }

    @Test fun interruptedCredentialDatabaseTransitionFailsClosedOnRestart() = runBlocking {
        val queue = FakeOutbox().apply { connection = AnnotationConnection(9, "database-connection", true) }
        val credentials = MemoryCredentials().apply { value = ReadwiseCredential("different-connection", "fixture") }
        Harness(queue, credentials) { error("Must not contact account") }.use { h ->
            h.session.drain()
            assertFalse(h.session.state.value.connected)
            assertFalse(queue.connection.exportEnabled)
            assertTrue(h.calls.requests.isEmpty())
        }
    }

    @Test fun pausingDuringAuthenticationCannotBeUndoneByItsLateResponse() = runBlocking {
        val queue = FakeOutbox()
        val credentials = MemoryCredentials()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var session: ReadwiseSession
        val calls = FakeReadwiseCalls { request ->
            runBlocking { session.setExportEnabled(false) }
            response(request, 204)
        }
        session = ReadwiseSession(queue, credentials, ReadwiseApi(calls), FakeScheduler(), scope)
        try {
            assertFalse(session.connect("synthetic-fixture", true).success)
            assertFalse(queue.connection.exportEnabled)
            assertNull(credentials.value)
        } finally { scope.cancel() }
    }

    @Test fun foregroundAndWorkerDrainsShareSerializedClaim() = runBlocking {
        harness { created(it) }.use { h ->
            h.connect(); h.queue.add("a"); h.queue.add("b")
            listOf(async(Dispatchers.Default) { h.session.drain() }, async(Dispatchers.Default) { h.session.drain() }).awaitAll()
            assertEquals(2, h.exportRequests().size)
            assertEquals(2, h.queue.acknowledged.size)
        }
    }

    private fun created(request: Request) = response(request, 200, """[{"modified_highlights":[44]}]""")
    private fun harness(reply: (Request) -> Response) = Harness(reply = reply)
}

private class Harness(
    val queue: FakeOutbox = FakeOutbox(),
    val credentials: MemoryCredentials = MemoryCredentials(),
    reply: (Request) -> Response,
) : AutoCloseable {
    var now = 1_700_000_000_000L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val calls = FakeReadwiseCalls { if (it.url.encodedPath.endsWith("auth/")) response(it, 204) else reply(it) }
    val scheduler = FakeScheduler()
    val session = ReadwiseSession(queue, credentials, ReadwiseApi(calls), scheduler, scope) { now }
    suspend fun connect() { assertTrue(session.connect("synthetic-fixture", true).success); session.drain() }
    fun exportRequests() = synchronized(calls.requests) { calls.requests.filterNot { it.url.encodedPath.endsWith("auth/") } }
    override fun close() { scope.cancel() }
}

private class MemoryCredentials : ReadwiseCredentialStore {
    var value: ReadwiseCredential? = null
    override fun read() = value
    override fun write(credential: ReadwiseCredential) { value = credential }
    override fun clear() { value = null }
}

private class FakeScheduler : ReadwiseDrainScheduler {
    val delays = mutableListOf<Long>()
    override fun enqueue(delayMillis: Long) { synchronized(delays) { delays += delayMillis } }
    override fun cancel() = Unit
}

private class FakeOutbox : AnnotationOutbox {
    var connection = AnnotationConnection()
    data class Row(var payload: AnnotationExportPayload, val epoch: Long, var operation: AnnotationExportOperation = AnnotationExportOperation.CREATE,
        var remoteId: String? = null, var next: Long = 0, var lease: String? = null, var attempts: Int = 0,
        var reconcile: Boolean = false, var review: Boolean = false, var failed: Boolean = false)
    val rows = mutableListOf<Row>()
    val currentNotes = mutableMapOf<String, String>()
    val acknowledged = mutableListOf<Pair<String, String>>()
    fun add(id: String) { rows += Row(AnnotationExportPayload(id, 1, "https://example.test/story", "Title", "Author", null, "quote", "original", 1_700_000_000_000), connection.epoch); currentNotes[id] = "original" }
    fun edit(id: String, note: String) { currentNotes[id] = note }
    override fun observeConnection(): Flow<AnnotationConnection> = MutableStateFlow(connection)
    override fun observePendingCount(): Flow<Int> = flowOf(0)
    override suspend fun currentConnection() = connection
    override suspend fun setConnection(connectionId: String?): Long { connection = AnnotationConnection(connection.epoch + 1, connectionId, false); return connection.epoch }
    override suspend fun setExportEnabled(enabled: Boolean) { connection = connection.copy(exportEnabled = enabled && connection.connectionId != null) }
    override suspend fun pauseIfCurrent(epoch: Long): Boolean { if (epoch != connection.epoch) return false; connection = connection.copy(exportEnabled = false); return true }
    override suspend fun deferUntil(epoch: Long, retryAt: Long): Boolean { if (epoch != connection.epoch) return false; connection = connection.copy(retryAt = maxOf(connection.retryAt ?: 0, retryAt)); return true }
    override suspend fun retryFailed(ids: List<String>, epoch: Long) { rows.filter { it.payload.annotationId in ids && it.epoch == epoch && it.failed && !it.review }.forEach { it.failed = false; it.next = 0 } }
    override suspend fun enqueueExisting(ids: List<String>) = error("Cross-connection reassignment must not be used")
    override suspend fun claim(epoch: Long, now: Long, leaseMillis: Long): AnnotationExportClaim? {
        if (epoch != connection.epoch || !connection.exportEnabled || (connection.retryAt ?: 0) > now) return null
        val row = rows.firstOrNull { it.epoch == epoch && it.next <= now && it.lease == null && !it.failed && !it.review } ?: return null
        row.attempts += 1; row.lease = "lease-${row.attempts}"
        return AnnotationExportClaim(row.payload.annotationId, epoch, row.lease!!, now + leaseMillis, row.attempts, row.reconcile, row.operation, row.remoteId, row.payload)
    }
    private fun matching(claim: AnnotationExportClaim) = if (connection.epoch != claim.epoch || !connection.exportEnabled) null else rows.find { it.epoch == claim.epoch && it.payload.annotationId == claim.id && it.lease == claim.leaseToken }
    override suspend fun acknowledge(claim: AnnotationExportClaim, remoteId: String): Boolean {
        val row = matching(claim) ?: return false
        acknowledged += claim.id to remoteId; rows.remove(row)
        val note = currentNotes[claim.id] ?: claim.payload.note
        if (note != claim.payload.note) rows += Row(claim.payload.copy(revision = claim.payload.revision + 1, note = note), claim.epoch, AnnotationExportOperation.UPDATE, remoteId)
        return true
    }
    override suspend fun fail(claim: AnnotationExportClaim, message: String, retryAt: Long?): Boolean {
        val row = matching(claim) ?: return false; row.lease = null; row.next = retryAt ?: 0; row.failed = retryAt == null; return true
    }
    override suspend fun markUncertain(claim: AnnotationExportClaim, message: String, retryAt: Long): Boolean {
        val row = matching(claim) ?: return false; row.reconcile = true; return fail(claim, message, retryAt)
    }
    override suspend fun requireReview(claim: AnnotationExportClaim, message: String): Boolean {
        val row = matching(claim) ?: return false; row.review = true; row.lease = null; return true
    }
    override suspend fun nextWakeAt(epoch: Long): Long? {
        if (epoch != connection.epoch || !connection.exportEnabled) return null
        return rows.filter { it.epoch == epoch && !it.failed && !it.review }.minOfOrNull { maxOf(it.next, connection.retryAt ?: 0) }
    }
}
