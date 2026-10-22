package me.ash.reader.ui.page.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.domain.library.SavedLibraryRepository

@Serializable
internal data class PendingLinkCapture(
    val token: String,
    val text: String,
    val titleHint: String? = null,
    val mode: String = LinkCaptureMode.CHOOSE.name,
    val selectedUrl: String? = null,
    val saving: Boolean = false,
)

/** Activity-scoped, account-independent and saved before any navigation or network scheduling. */
@HiltViewModel
class SavedCaptureViewModel @Inject constructor(
    private val repository: SavedLibraryRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    internal val queue: StateFlow<String> = savedState.getStateFlow("saved_capture_queue", "[]")
    val error: StateFlow<String?> = savedState.getStateFlow("saved_capture_error", null)
    val savedItemId: StateFlow<String?> = savedState.getStateFlow("saved_capture_result", null)
    val savedNoticeId: StateFlow<String?> = savedState.getStateFlow("saved_capture_notice_id", null)
    internal fun pending(): List<PendingLinkCapture> = runCatching {
        Json.decodeFromString<List<PendingLinkCapture>>(queue.value)
    }.getOrDefault(emptyList())

    init {
        // If the process died after Save was chosen, repeat the idempotent URL transaction.
        pending().firstOrNull()?.takeIf { it.saving }?.let { save(it) }
    }

    internal fun enqueue(text: String, title: String? = null, mode: LinkCaptureMode = LinkCaptureMode.CHOOSE) {
        if (text.length > 32_768) {
            savedState["saved_capture_error"] = "This share is too long. Paste just the article link in Saved."
            return
        }
        val current = pending()
        if (current.size >= 8) {
            savedState["saved_capture_error"] = "Finish or cancel an existing shared link before adding another."
            return
        }
        val input = parseSharedLinks(text, title)
        val request = PendingLinkCapture(UUID.randomUUID().toString(), text, input.titleHint, mode.name,
            input.urls.singleOrNull())
        val next = current + request
        // Bound encoded state too: control-character escaping can expand otherwise small shares.
        if (Json.encodeToString(next).length > 128 * 1024) {
            savedState["saved_capture_error"] = "Finish or cancel an existing shared link before adding another."
            return
        }
        write(next)
    }

    internal fun select(token: String, url: String) {
        val first = pending().firstOrNull() ?: return
        if (first.token != token || first.saving || url !in parseSharedLinks(first.text).urls) return
        replace(first.copy(selectedUrl = url))
    }

    internal fun dismiss(token: String) {
        val first = pending().firstOrNull() ?: return
        if (first.token == token && !first.saving) {
            write(pending().drop(1))
            clearError()
        }
    }

    internal fun subscribe(token: String): String? {
        val first = pending().firstOrNull() ?: return null
        if (first.token != token || first.saving) return null
        val url = first.selectedUrl ?: return null
        dismiss(token)
        return url
    }

    internal fun saveSelected(token: String) {
        val first = pending().firstOrNull() ?: return
        if (first.token != token || first.saving || first.selectedUrl == null) return
        val chosen = first.copy(saving = true)
        replace(chosen) // Persist intent before starting the durable transaction.
        clearError()
        save(chosen)
    }

    private fun save(request: PendingLinkCapture) {
        val url = request.selectedUrl ?: return
        viewModelScope.launch {
            try {
                val id = repository.saveUrl(url, request.titleHint)
                // Do not navigate before the durable row exists, including duplicate captures.
                savedState["saved_capture_result"] = id
                savedState["saved_capture_notice_id"] = id
                write(pending().filterNot { it.token == request.token })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                replace(request.copy(saving = false))
                savedState["saved_capture_error"] = "The link could not be saved. Try again."
            }
        }
    }

    fun dismissSavedNotice() { savedState["saved_capture_notice_id"] = null }
    fun acknowledgeSaved() { savedState["saved_capture_result"] = null }
    fun clearError() { savedState["saved_capture_error"] = null }
    private fun replace(request: PendingLinkCapture) = write(pending().map { if (it.token == request.token) request else it })
    private fun write(requests: List<PendingLinkCapture>) { savedState["saved_capture_queue"] = Json.encodeToString(requests) }
}
