package me.ash.reader.ui.page.home.reading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Own the output before crossing the cancellable dispatcher return boundary. */
internal suspend fun <T : Any> withEInkDocument(
    build: () -> T,
    dispose: (T) -> Unit,
    use: suspend (T) -> Unit,
) {
    var owned: T? = null
    try {
        // Skip work that was cancelled before dispatch, while retaining ownership if a
        // synchronous builder finishes during cancellation. Only cleanup is non-cancellable.
        withContext(Dispatchers.IO) { owned = build() }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        use(checkNotNull(owned))
    } finally {
        owned?.let { value -> withContext(Dispatchers.IO + NonCancellable) { dispose(value) } }
    }
}
