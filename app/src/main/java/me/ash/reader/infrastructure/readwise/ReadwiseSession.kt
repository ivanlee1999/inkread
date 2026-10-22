package me.ash.reader.infrastructure.readwise

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.domain.annotation.AnnotationExportOperation
import me.ash.reader.domain.annotation.AnnotationOutbox
import java.util.UUID

/** Public UI state intentionally contains no token, token suffix, or account-identity claim. */
data class ReadwiseConnectionState(
    val connected: Boolean = false,
    val exportEnabled: Boolean = false,
    val epoch: Long = 0,
    val busy: Boolean = true,
    val error: String? = null,
)

data class ReadwiseConnectResult(val success: Boolean, val error: String? = null)

internal interface ReadwiseDrainScheduler {
    fun enqueue(delayMillis: Long = 0)
    fun cancel()
}

/** Testable core. All persistent payloads belong to the durable AnnotationOutbox. */
internal class ReadwiseSession(
    private val outbox: AnnotationOutbox,
    private val credentials: ReadwiseCredentialStore,
    private val api: ReadwiseApi,
    private val scheduler: ReadwiseDrainScheduler,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(ReadwiseConnectionState())
    val state: StateFlow<ReadwiseConnectionState> = mutableState.asStateFlow()
    private val transitions = Mutex()
    private val drains = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private var generation = 0L

    init {
        scope.launch {
            try {
                transitions.withLock { refreshConnection() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                api.activate(null)
                mutableState.value = ReadwiseConnectionState(busy = false, error = "Readwise export could not be initialized. Local annotations remain safe.")
            } finally { initialized.complete(Unit) }
            outbox.observePendingCount().distinctUntilChanged().collect { count -> if (count > 0) requestDrain() }
        }
    }

    suspend fun connect(token: String, exportEnabled: Boolean): ReadwiseConnectResult {
        initialized.await()
        if (token.isBlank() || token.length > 4096 || token.any { it.code !in 33..126 })
            return ReadwiseConnectResult(false, "Enter the Readwise access token without spaces.")
        val ticket = withContext(NonCancellable) { transitions.withLock {
            generation += 1
            api.activate(null)
            scheduler.cancel()
            outbox.setExportEnabled(false)
            mutableState.value = mutableState.value.copy(exportEnabled = false, busy = true, error = null)
            generation
        } }
        val validation = try {
            withContext(Dispatchers.IO) { api.authenticate(token) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                transitions.withLock {
                    if (ticket == generation) {
                        generation += 1
                        api.activate(null)
                        mutableState.value = mutableState.value.copy(busy = false, exportEnabled = false, error = "Connection was cancelled.")
                    }
                }
            }
            throw cancelled
        }
        return transitions.withLock {
            if (ticket != generation) return@withLock ReadwiseConnectResult(false, "Connection was cancelled.")
            if (validation !is ReadwiseResult.Success) {
                val message = when (validation) {
                    ReadwiseResult.Unauthorized -> "Readwise rejected this token. Check the token and try again."
                    is ReadwiseResult.Invalid -> validation.message
                    is ReadwiseResult.RateLimited -> "Readwise is rate limiting requests. Wait before reconnecting."
                    else -> "Readwise could not validate the token. Try again when connected."
                }
                mutableState.value = mutableState.value.copy(busy = false, error = message)
                return@withLock ReadwiseConnectResult(false, message)
            }
            try {
                val connectionId = UUID.randomUUID().toString()
                // Crash between these writes produces an ID mismatch. Startup then fails closed.
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { credentials.write(ReadwiseCredential(connectionId, token)) }
                    val epoch = outbox.setConnection(connectionId)
                    outbox.setExportEnabled(exportEnabled)
                    api.activate(if (exportEnabled) epoch else null)
                    mutableState.value = ReadwiseConnectionState(true, exportEnabled, epoch, busy = false)
                    if (exportEnabled) requestDrain()
                }
                ReadwiseConnectResult(true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                api.activate(null)
                outbox.setExportEnabled(false)
                mutableState.value = ReadwiseConnectionState(busy = false, error = "The token could not be stored securely. Reconnect to enable export.")
                ReadwiseConnectResult(false, mutableState.value.error)
            }
        }
    }

    suspend fun setExportEnabled(enabled: Boolean) {
        initialized.await()
        withContext(NonCancellable) { transitions.withLock {
            generation += 1 // A later pause/resume supersedes a still-validating Connect request.
            if (!enabled) {
                api.activate(null)
                scheduler.cancel()
                outbox.setExportEnabled(false)
                mutableState.value = mutableState.value.copy(exportEnabled = false, busy = false)
            } else {
                refreshConnection()
                if (!mutableState.value.connected) return@withLock
                outbox.setExportEnabled(true)
                api.activate(mutableState.value.epoch)
                mutableState.value = mutableState.value.copy(exportEnabled = true, error = null)
                requestDrain()
            }
        } }
    }

    suspend fun disconnect() {
        initialized.await()
        withContext(NonCancellable) { transitions.withLock {
            generation += 1
            api.activate(null) // Best effort: a request already received by Readwise cannot be recalled.
            scheduler.cancel()
            val epoch = outbox.setConnection(null)
            var error: String? = null
            try { withContext(Dispatchers.IO) { credentials.clear() } }
            catch (_: Exception) { error = "Export is disconnected, but secure token cleanup failed. Try disconnecting again." }
            mutableState.value = ReadwiseConnectionState(epoch = epoch, busy = false, error = error)
        } }
    }

    fun requestDrain() {
        if (!mutableState.value.exportEnabled) return
        scheduler.enqueue()
        scope.launch {
            try { drain() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Preserve the durable queue on a transient local DB/scheduler failure.
                scheduler.enqueue(30_000)
                mutableState.value = mutableState.value.copy(error = "Readwise export was interrupted locally. A retry is scheduled; annotations remain saved.")
            }
        }
    }

    suspend fun retryAnnotations(ids: List<String>) {
        initialized.await()
        transitions.withLock {
            val connection = outbox.currentConnection()
            if (!connection.exportEnabled || !mutableState.value.connected) return@withLock
            outbox.retryFailed(ids, connection.epoch)
            requestDrain()
        }
    }

    /** Foreground and WorkManager share the same mutex AND the same persisted fenced claim. */
    suspend fun drain() {
        initialized.await()
        drains.withLock {
            repeat(100) {
                val connection = outbox.currentConnection()
                if (!connection.exportEnabled || !mutableState.value.exportEnabled) return
                val credential = try { withContext(Dispatchers.IO) { credentials.read() } }
                    catch (_: Exception) { null }
                if (credential == null || credential.connectionId != connection.connectionId) {
                    transitions.withLock {
                        if (outbox.pauseIfCurrent(connection.epoch)) {
                            api.activate(null)
                            mutableState.value = ReadwiseConnectionState(epoch = connection.epoch, busy = false,
                                error = "The secure token does not match this connection. Reconnect; older queued annotations will remain paused.")
                        }
                    }
                    return
                }
                val claim = outbox.claim(connection.epoch, now(), LEASE_MILLIS)
                if (claim == null) {
                    scheduleNext(connection.epoch)
                    return
                }
                val payload = claim.payload.let { ReadwiseHighlightPayload(it.quote, it.note, it.title, it.author, it.sourceUrl, it.highlightedAt, it.location) }
                val result = withContext(Dispatchers.IO) {
                    when (claim.operation) {
                        AnnotationExportOperation.CREATE -> if (claim.needsReconciliation)
                            api.reconcileCreate(claim.epoch, credential.token, payload)
                            else api.create(claim.epoch, credential.token, payload)
                        AnnotationExportOperation.UPDATE -> claim.remoteId?.let { api.update(claim.epoch, credential.token, it, payload) }
                            ?: ReadwiseResult.Invalid("The Readwise highlight ID is missing. Review this annotation before exporting again.")
                    }
                }
                when (result) {
                    is ReadwiseResult.Success -> outbox.acknowledge(claim, result.value)
                    ReadwiseResult.Unauthorized, ReadwiseResult.ReconciliationUnauthorized -> {
                        transitions.withLock {
                            if (outbox.currentConnection().epoch == claim.epoch) {
                                // A 401 during reconciliation must not turn a committed CREATE into a fresh POST.
                                if (result == ReadwiseResult.ReconciliationUnauthorized)
                                    outbox.markUncertain(claim, "The highlight may already exist, but Readwise rejected ID reconciliation (HTTP 401). Export is paused.", now())
                                else outbox.fail(claim, "Readwise rejected the token (HTTP 401). Export is paused.", now())
                                if (outbox.pauseIfCurrent(claim.epoch)) {
                                    api.activate(null)
                                    scheduler.cancel()
                                    mutableState.value = mutableState.value.copy(exportEnabled = false, error = "Readwise rejected the token. Export is paused; reconnect to use a new token.")
                                }
                            }
                        }
                        return
                    }
                    is ReadwiseResult.Invalid -> outbox.fail(claim, result.message, null)
                    is ReadwiseResult.ReconciliationRequired -> outbox.requireReview(claim, result.message)
                    is ReadwiseResult.ReconciliationDeferred -> {
                        val retryAt = later(result.delayMillis)
                        outbox.deferUntil(claim.epoch, retryAt)
                        outbox.markUncertain(claim, "Readwise created or de-duplicated this highlight. Read-only ID reconciliation is waiting for its rate limit.", retryAt)
                        scheduleNext(claim.epoch)
                        return
                    }
                    is ReadwiseResult.ReconciliationThrottled -> {
                        outbox.deferUntil(claim.epoch, later(result.delayMillis))
                        outbox.requireReview(claim, "Readwise throttled a multi-page reconciliation scan. Review this annotation in Readwise; InkRead will not repeatedly scan your library or resend it.")
                        scheduleNext(claim.epoch)
                        return
                    }
                    is ReadwiseResult.RateLimited -> {
                        val retryAt = later(result.delayMillis)
                        outbox.deferUntil(claim.epoch, retryAt)
                        outbox.fail(claim, "Readwise rate limit reached. A retry is scheduled.", retryAt)
                        scheduleNext(claim.epoch)
                        return // Do not keep hitting the same token with other queued items.
                    }
                    ReadwiseResult.NotSentRetryable -> {
                        val retryAt = later(backoffMillis(claim.attemptCount))
                        outbox.deferUntil(claim.epoch, retryAt)
                        outbox.fail(claim, "Readwise was unreachable before the request was sent. A retry is scheduled.", retryAt)
                        scheduleNext(claim.epoch)
                        return
                    }
                    ReadwiseResult.Retryable -> {
                        val retryAt = later(backoffMillis(claim.attemptCount))
                        outbox.deferUntil(claim.epoch, retryAt)
                        if (claim.operation == AnnotationExportOperation.CREATE)
                            outbox.markUncertain(claim, "Readwise could not confirm creation. A read-only reconciliation is scheduled.", retryAt)
                        else outbox.fail(claim, "Readwise could not confirm the update. Retrying the unchanged edit.", retryAt)
                        scheduleNext(claim.epoch)
                        return
                    }
                    ReadwiseResult.Cancelled -> {
                        // Pause/resume can race a cancelled call's completion in the SAME epoch.
                        // Cancellation does not prove the server missed CREATE, so reconcile first.
                        if (claim.operation == AnnotationExportOperation.CREATE)
                            outbox.markUncertain(claim, "Readwise creation was interrupted. Read-only reconciliation is required before any further send.", now())
                        else outbox.fail(claim, "Readwise update was interrupted. The unchanged edit can be retried.", now())
                        scheduleNext(claim.epoch)
                        return
                    }
                }
            }
            scheduleNext(outbox.currentConnection().epoch)
        }
    }

    private suspend fun refreshConnection() {
        val connection = outbox.currentConnection()
        val credential = try { withContext(Dispatchers.IO) { credentials.read() } } catch (_: Exception) { null }
        val matches = credential != null && credential.connectionId == connection.connectionId
        if (!matches && connection.exportEnabled) outbox.pauseIfCurrent(connection.epoch)
        api.activate(if (matches && connection.exportEnabled) connection.epoch else null)
        mutableState.value = ReadwiseConnectionState(matches, matches && connection.exportEnabled, connection.epoch, busy = false,
            error = if (!matches && connection.connectionId != null) "The secure token is unavailable or belongs to another connection. Reconnect to enable export." else null)
    }

    private suspend fun scheduleNext(epoch: Long) {
        outbox.nextWakeAt(epoch)?.let { scheduler.enqueue((it - now()).coerceAtLeast(0)) }
    }

    private fun later(delay: Long): Long = now().let { if (delay > Long.MAX_VALUE - it) Long.MAX_VALUE else it + delay }
    companion object { private const val LEASE_MILLIS = 120_000L }
}

internal fun backoffMillis(attempt: Int): Long = (30_000L * (1L shl (attempt - 1).coerceIn(0, 7))).coerceAtMost(3_600_000L)
