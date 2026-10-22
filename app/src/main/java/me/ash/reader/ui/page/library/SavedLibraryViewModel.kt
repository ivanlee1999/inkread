package me.ash.reader.ui.page.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ash.reader.domain.library.SavedItem
import me.ash.reader.domain.library.SavedLibraryRepository
import me.ash.reader.domain.library.SavedLocation

internal enum class SavedReadFilter(val label: String) { ALL("All articles"), UNREAD("Unread"), READ("Read") }

@HiltViewModel
class SavedLibraryViewModel @Inject constructor(
    private val repository: SavedLibraryRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val items: StateFlow<List<SavedItem>?> = repository.observeItems(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    internal val location = savedState.getStateFlow("saved_location", SavedLocation.INBOX.name)
    internal val readFilter = savedState.getStateFlow("saved_read_filter", SavedReadFilter.ALL.name)
    val message = savedState.getStateFlow<String?>("saved_library_message", null)
    private val editions = mutableMapOf<String, SavedLibraryEditionState>()

    internal fun edition(key: String): SavedLibraryEditionState = editions.getOrPut(key) {
        SavedLibraryEditionState(savedState["saved_anchor_$key"], savedState["saved_page_$key"] ?: 0) { anchor, page ->
            savedState["saved_anchor_$key"] = anchor
            savedState["saved_page_$key"] = page
        }
    }
    internal fun selectLocation(value: SavedLocation) { savedState["saved_location"] = value.name }
    internal fun selectReadFilter(value: SavedReadFilter) { savedState["saved_read_filter"] = value.name }
    fun move(id: String, location: SavedLocation) = action { repository.move(id, location) }
    fun setRead(id: String, value: Boolean) = action { repository.setRead(id, value) }
    fun retry(id: String) = action { repository.retry(id) }
    fun remove(id: String) = action { repository.remove(id) }
    fun clearMessage() { savedState["saved_library_message"] = null }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { savedState["saved_library_message"] = "That change could not be saved. Please try again." }
        }
    }
}
