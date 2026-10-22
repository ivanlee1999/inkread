package me.ash.reader.infrastructure.preference

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/**
 * Retry short-lived disk failures without emitting replacement preferences or losing the last good
 * snapshot. A manual retry can resubscribe to this flow, but only a real emission clears its error.
 * The delay hook keeps recovery tests deterministic without Android or a wall-clock wait.
 */
internal fun <T> Flow<T>.recoverPreferenceReadErrors(
    onRead: () -> Unit,
    onFailure: (Throwable) -> Unit,
    waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): Flow<T> = onEach { onRead() }
    .retryWhen { cause, attempt ->
        if (cause is IOException && attempt < PreferenceReadRetryDelays.size) {
            // A cached snapshot is still usable, but it must not conceal a failed disk read.
            onFailure(cause)
            waitBeforeRetry(PreferenceReadRetryDelays[attempt.toInt()])
            true
        } else {
            false
        }
    }
    .catch { cause ->
        if (cause is CancellationException) throw cause
        onFailure(cause)
    }

private val PreferenceReadRetryDelays = listOf(250L, 500L, 1_000L)
