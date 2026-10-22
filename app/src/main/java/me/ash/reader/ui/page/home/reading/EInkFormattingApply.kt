package me.ash.reader.ui.page.home.reading

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A persistence completion may resume on a worker under a caller's continuation
 * interceptor. Explicitly restore Android's UI dispatcher before touching the
 * WebView or Compose state, and never report a post-save UI error as a failed save.
 */
internal suspend fun <T> applyEInkFormatting(
    persist: suspend () -> T,
    onSaved: (T) -> Unit,
    onSaveFailed: (Exception) -> Unit,
    onFinished: () -> Unit,
    uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) = withContext(uiDispatcher) {
    try {
        val saved = try {
            persist()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onSaveFailed(failure)
            return@withContext
        }
        onSaved(saved)
    } finally {
        onFinished()
    }
}
