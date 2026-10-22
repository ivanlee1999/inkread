package me.ash.reader.ui.page.annotation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.ash.reader.domain.annotation.Annotation
import me.ash.reader.domain.annotation.AnnotationDraft
import me.ash.reader.domain.annotation.AnnotationRepository
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSizeException
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.readwise.ReadwiseConnectionManager

data class AnnotationDocumentState(
    val annotations: List<Annotation> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Read-only data plus local edits; keeps UI tests independent of credentials and HTTP. */
interface AnnotationReaderActions {
    fun observe(source: AnnotationSource?): Flow<AnnotationDocumentState>
    fun create(draft: AnnotationDraft, onResult: (String?) -> Unit)
    fun updateNote(id: String, note: String, onResult: (String?) -> Unit)
    fun remove(id: String, onResult: (String?) -> Unit)
    fun retry(id: String, onResult: (String?) -> Unit)
}

@HiltViewModel
class AnnotationReaderViewModel @Inject constructor(
    private val repository: AnnotationRepository,
    private val readwise: ReadwiseConnectionManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel(), AnnotationReaderActions {
    override fun observe(source: AnnotationSource?): Flow<AnnotationDocumentState> =
        if (source == null) flowOf(AnnotationDocumentState(loading = false))
        else repository.observeDocument(source)
            .map { AnnotationDocumentState(it, loading = false) }
            .catch { emit(AnnotationDocumentState(loading = false, error = "Could not load local annotations. Reopen this article to try again.")) }

    override fun create(draft: AnnotationDraft, onResult: (String?) -> Unit) = change(onResult) {
        repository.create(draft)
    }

    override fun updateNote(id: String, note: String, onResult: (String?) -> Unit) = change(onResult) {
        repository.updateNote(id, note)
    }

    override fun remove(id: String, onResult: (String?) -> Unit) = change(onResult) {
        repository.remove(id)
    }

    override fun retry(id: String, onResult: (String?) -> Unit) = change(onResult) {
        readwise.retryAnnotations(listOf(id))
    }

    private fun change(onResult: (String?) -> Unit, action: suspend () -> Unit) {
        // A locally accepted edit finishes even if its reader is closed. No selection
        // is ever re-resolved against whatever article happens to be open later.
        applicationScope.launch {
            try {
                action()
                // Scheduling is best effort after the local transaction commits. A
                // scheduler failure must never misreport a durable local save as lost.
                runCatching { readwise.requestDrain() }
                withContext(Dispatchers.Main.immediate) { onResult(null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) { onResult(annotationSaveError(failure)) }
            }
        }
    }
}

/** Only the storage boundary's fixed, actionable size error is safe to surface verbatim. */
internal fun annotationSaveError(failure: Exception): String =
    if (failure is AnnotationSizeException) {
        failure.message ?: "This annotation is too large to save. Select a shorter passage or shorten the note. Nothing was changed."
    } else "Could not save this change on your device. Please try again."
