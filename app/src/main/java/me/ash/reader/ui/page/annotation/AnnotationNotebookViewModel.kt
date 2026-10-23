package me.ash.reader.ui.page.annotation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.ash.reader.domain.annotation.Annotation
import me.ash.reader.domain.annotation.AnnotationRepository
import me.ash.reader.domain.annotation.AnnotationSourceKind
import me.ash.reader.domain.library.SavedLibraryRepository
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.service.AccountService

@OptIn(FlowPreview::class)
@HiltViewModel
class AnnotationNotebookViewModel @Inject constructor(
    repository: AnnotationRepository,
    private val articles: ArticleDao,
    private val savedLibrary: SavedLibraryRepository,
    private val accounts: AccountService,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    internal val currentAccountId = accounts.currentAccountIdFlow
    private val annotations = repository.observeAll()
        .map { AnnotationDocumentState(it, loading = false) }
        .catch { emit(AnnotationDocumentState(loading = false, error = "Could not load local highlights. Leave and reopen Highlights to try again.")) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnotationDocumentState())
    internal val searchText = savedState.getStateFlow("notebook_search", "")
    private val notes = savedState.getStateFlow("notebook_notes", false)
    private val unsynced = savedState.getStateFlow("notebook_unsynced", false)
    private val page = savedState.getStateFlow("notebook_page", 0)
    private val listIndex = savedState.getStateFlow("notebook_list_index", 0)
    private val scrollOffset = savedState.getStateFlow("notebook_scroll_offset", 0)
    private val selection = combine(searchText.debounce(180), notes, unsynced, page) { text, withNotes, pending, pageIndex ->
        NotebookSelection(text, withNotes, pending, pageIndex)
    }
    private val filtered = combine(annotations, selection) { document, selected ->
        annotationNotebookPage(document.annotations, selected).copy(loading = document.loading, error = document.error)
    }.flowOn(Dispatchers.Default)
    internal val state = notebookWithPosition(filtered, listIndex, scrollOffset)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnotationNotebookState())

    internal fun savedSourceAvailable(id: String) = savedLibrary.observeItem(id)
        .map { it != null }.catch { emit(false) }

    internal fun observeAnnotation(id: String) = annotations.map { document ->
        document.copy(annotations = document.annotations.filter { it.id == id })
    }

    internal fun search(value: String) { resetPosition(); savedState["notebook_search"] = value }
    internal fun notesOnly(value: Boolean) { resetPosition(); savedState["notebook_notes"] = value }
    internal fun unsyncedOnly(value: Boolean) { resetPosition(); savedState["notebook_unsynced"] = value }
    internal fun page(value: Int) { resetPosition(); savedState["notebook_page"] = value.coerceAtLeast(0) }
    internal fun position(index: Int, offset: Int) {
        savedState["notebook_list_index"] = index.coerceAtLeast(0)
        savedState["notebook_scroll_offset"] = offset.coerceAtLeast(0)
    }
    private fun resetPosition() { position(0, 0); savedState["notebook_page"] = 0 }

    /** Read-only local lookup. Do not change RSS accounts or revive removed source articles. */
    internal suspend fun checkSource(annotation: Annotation): String? = try {
        when (annotation.source.kind) {
            AnnotationSourceKind.RSS -> {
                val article = articles.queryById(annotation.source.sourceId)?.article
                notebookSourceAvailability(annotation.source, article?.id, article?.accountId,
                    accounts.currentAccountIdFlow.value).message
            }
            AnnotationSourceKind.SAVED -> {
                val item = savedLibrary.observeItem(annotation.source.sourceId).first()
                notebookSourceAvailability(annotation.source, item?.id).message
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { "Could not open the original article. Your quote and note are still saved. Please try again." }
}
