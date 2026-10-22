package me.ash.reader.ui.page.library

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One in-flight disk write plus one latest pending anchor, in accepted-turn order. */
internal class SavedReadingProgressWriter(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val save: suspend (String) -> Unit,
    private val onError: (Boolean) -> Unit,
) {
    private data class Entry(val sequence: Long, val json: String)
    private data class Completion(val sequence: Long = 0, val successful: Boolean = true)
    private val sequence = AtomicLong()
    private val pending = Channel<Entry>(Channel.CONFLATED)
    private val completed = MutableStateFlow(Completion())
    private var closed = false
    init { scope.launch(dispatcher) {
        var lastSuccessful: String? = null
        for (entry in pending) {
            var success = false
            try {
                if (lastSuccessful != entry.json) save(entry.json)
                lastSuccessful = entry.json
                success = true
                onError(false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { onError(true)
            } finally { completed.value = Completion(entry.sequence, success) }
        }
    } }

    @Synchronized
    fun submit(json: String): Boolean {
        if (closed) return false
        return pending.trySend(Entry(sequence.incrementAndGet(), json)).isSuccess
    }

    /** Snapshot barrier, including the newest conflated value accepted before this call. */
    suspend fun drain(): Boolean {
        val target = sequence.get()
        return completed.first { it.sequence >= target }.successful
    }

    /** Drain the last pending entry even when the navigation-scoped VM is cleared. */
    @Synchronized
    fun close() { closed = true; pending.close() }
}
