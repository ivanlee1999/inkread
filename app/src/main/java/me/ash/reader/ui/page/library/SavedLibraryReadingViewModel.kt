package me.ash.reader.ui.page.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.library.SavedItem
import me.ash.reader.domain.library.SavedLibraryRepository
import me.ash.reader.domain.library.SavedLocation
import me.ash.reader.domain.library.SavedReadingDocument
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher

data class SavedLibraryReadingState(
    val itemId: String = "",
    val item: SavedItem? = null,
    val document: SavedReadingDocument? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val positionError: Boolean = false,
)

/** Saved IDs and read state never enter the RSS reader or its list DiffMap. */
@HiltViewModel
class SavedLibraryReadingViewModel @Inject constructor(
    private val repository: SavedLibraryRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SavedLibraryReadingState())
    val state = mutableState.asStateFlow()
    private var itemJob: Job? = null
    private var exitJob: Job? = null
    private var exitCommitted = false
    private var cleared = false
    private var latestAnchor: AnchorWrite? = null
    private var writer: SavedReadingProgressWriter? = null
    private var writerIdentity: Pair<String, String>? = null

    fun open(itemId: String) {
        if (mutableState.value.itemId == itemId && itemJob?.isActive == true) return
        itemJob?.cancel()
        exitJob?.cancel()
        exitCommitted = false
        writer?.close()
        writer = null
        writerIdentity = null
        latestAnchor = null
        mutableState.value = SavedLibraryReadingState(itemId = itemId)
        itemJob = viewModelScope.launch {
            try {
                repository.observeItem(itemId).collectLatest { item ->
                    mutableState.update { it.copy(item = item, loading = it.document == null) }
                    try {
                        val document = if (item == null) null else withContext(ioDispatcher) { repository.loadDocument(itemId) }
                        mutableState.update { it.copy(item = item, document = document, loading = false, error = null) }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) {
                        // Stay subscribed so an explicit download retry can recover.
                        mutableState.update { it.copy(loading = false, error = "Could not open this saved article.") }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = "Could not open this saved article.") }
            }
        }
    }

    fun move(location: SavedLocation) = change { id -> repository.move(id, location) }
    fun setRead(value: Boolean) = change { id -> repository.setRead(id, value) }
    fun retry() {
        change { id -> repository.retry(id) }
        if (itemJob?.isActive != true) open(mutableState.value.itemId)
    }

    private fun change(action: suspend (String) -> Unit) {
        val id = mutableState.value.itemId
        applicationScope.launch(ioDispatcher) {
            try {
                action(id)
                mutableState.update { if (it.itemId == id) it.copy(error = null) else it }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableState.update { if (it.itemId == id) it.copy(error = "Could not update this saved article. Try again.") else it }
            }
        }
    }

    fun saveAnchor(itemId: String, contentRevision: String, json: String) {
        if (cleared) return
        val document = mutableState.value.document ?: return
        if (mutableState.value.itemId != itemId || document.contentRevision != contentRevision) return
        val write = AnchorWrite(itemId, contentRevision, json)
        latestAnchor = write
        val identity = itemId to contentRevision
        if (writerIdentity != identity) {
            writer?.close()
            writerIdentity = identity
            writer = SavedReadingProgressWriter(applicationScope, ioDispatcher,
                save = { repository.saveReadingAnchor(itemId, contentRevision, it) },
                onError = { failed -> mutableState.update {
                    if (it.itemId == itemId) it.copy(positionError = failed) else it
                } },
            )
        }
        writer!!.submit(json)
    }

    fun exit(onBack: () -> Unit) {
        if (exitCommitted || exitJob?.isActive == true) return
        exitJob = viewModelScope.launch {
            // The reader exports JS before reaching here. Keep its volume owner
            // mounted until the newest accepted anchor has finished on disk.
            if (writer?.drain() != false && !exitCommitted) { exitCommitted = true; onBack() }
        }
    }

    override fun onCleared() {
        cleared = true
        writer?.close()
        super.onCleared()
    }

    fun retryPositionSave() { latestAnchor?.let { saveAnchor(it.itemId, it.revision, it.json) } }
    private data class AnchorWrite(val itemId: String, val revision: String, val json: String)
}
