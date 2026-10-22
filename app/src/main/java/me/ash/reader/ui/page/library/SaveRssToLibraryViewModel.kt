package me.ash.reader.ui.page.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.ash.reader.domain.library.SavedLibraryRepository

/** Immutable visible content, captured before another RSS article can replace it. */
data class RssLibrarySnapshot(
    val url: String,
    val title: String,
    val author: String?,
    val sourceName: String?,
    val html: String?,
)

@HiltViewModel
class SaveRssToLibraryViewModel @Inject constructor(
    private val library: SavedLibraryRepository,
) : ViewModel() {
    private val pendingUrls = mutableSetOf<String>()
    private val results = Channel<SaveRssLibraryResult>(Channel.BUFFERED)
    val events = results.receiveAsFlow()

    fun save(snapshot: RssLibrarySnapshot) {
        if (snapshot.url.isBlank() || !pendingUrls.add(snapshot.url)) return
        viewModelScope.launch {
            try {
                val html = snapshot.html?.takeIf(String::isNotBlank)
                if (html == null) {
                    library.saveUrl(snapshot.url, snapshot.title)
                } else {
                    library.saveRssSnapshot(
                        url = snapshot.url,
                        title = snapshot.title,
                        author = snapshot.author,
                        sourceName = snapshot.sourceName,
                        html = html,
                    )
                }
                // A successful result is sent only after the durable save returns.
                results.send(SaveRssLibraryResult(saved = true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                results.send(SaveRssLibraryResult(saved = false))
            } finally {
                pendingUrls.remove(snapshot.url)
            }
        }
    }
}

data class SaveRssLibraryResult(val saved: Boolean)
