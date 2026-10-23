package me.ash.reader.ui.page.annotation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.ash.reader.domain.annotation.Annotation
import me.ash.reader.domain.annotation.AnnotationExportState
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.domain.annotation.AnnotationSourceKind

internal const val NOTEBOOK_PAGE_SIZE = 30

/** Local-only is intentional, not a failed or pending export. */
internal fun Annotation.isNotebookUnsynced(): Boolean = when (exportState) {
    AnnotationExportState.LOCAL_ONLY, AnnotationExportState.EXPORTED -> false
    AnnotationExportState.QUEUED, AnnotationExportState.SENDING, AnnotationExportState.FAILED,
    AnnotationExportState.REVIEW_REQUIRED, AnnotationExportState.PAUSED -> true
}

internal data class NotebookSelection(
    val search: String = "",
    val notesOnly: Boolean = false,
    val unsyncedOnly: Boolean = false,
    val page: Int = 0,
    val listIndex: Int = 0,
    val scrollOffset: Int = 0,
)

internal data class AnnotationNotebookState(
    val items: List<Annotation> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 1,
    val selection: NotebookSelection = NotebookSelection(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Stable creation ordering prevents a note edit or queue update moving the row being read. */
internal fun annotationNotebookPage(all: List<Annotation>, selection: NotebookSelection): AnnotationNotebookState {
    val terms = selection.search.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val matches = all.asSequence()
        .filter { !selection.notesOnly || it.note.isNotBlank() }
        .filter { !selection.unsyncedOnly || it.isNotebookUnsynced() }
        .filter { annotation ->
            val fields = listOf(annotation.quote, annotation.note, annotation.source.title,
                annotation.source.sourceName.orEmpty(), annotation.source.author.orEmpty(), annotation.source.url.orEmpty())
            terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }
        }
        .sortedWith(compareByDescending<Annotation> { it.createdAt }.thenBy { it.id })
        .toList()
    val count = ((matches.size + NOTEBOOK_PAGE_SIZE - 1) / NOTEBOOK_PAGE_SIZE).coerceAtLeast(1)
    val page = selection.page.coerceIn(0, count - 1)
    return AnnotationNotebookState(
        items = matches.drop(page * NOTEBOOK_PAGE_SIZE).take(NOTEBOOK_PAGE_SIZE),
        total = matches.size, page = page, pageCount = count, selection = selection, loading = false,
    )
}

internal enum class NotebookSourceAvailability(val message: String?) {
    AVAILABLE(null),
    MISSING("The original article is no longer available on this device. Your quote and note are still saved."),
    OTHER_ACCOUNT("Switch to the original RSS account to open this article. Your quote and note are still saved here."),
    IDENTITY_MISMATCH("The original article could not be verified. Your quote and note are still saved; no other article was opened."),
}

/** Never resolve an old highlight against another account's article with the same source ID. */
internal fun notebookSourceAvailability(
    source: AnnotationSource,
    resolvedId: String?,
    resolvedAccountId: Int? = null,
    currentAccountId: Int? = null,
): NotebookSourceAvailability = when {
    resolvedId == null -> NotebookSourceAvailability.MISSING
    resolvedId != source.sourceId -> NotebookSourceAvailability.IDENTITY_MISMATCH
    source.kind == AnnotationSourceKind.RSS &&
        (source.accountId == null || source.accountId != resolvedAccountId) -> NotebookSourceAvailability.IDENTITY_MISMATCH
    source.kind == AnnotationSourceKind.RSS && source.accountId != currentAccountId -> NotebookSourceAvailability.OTHER_ACCOUNT
    else -> NotebookSourceAvailability.AVAILABLE
}

internal fun sameNotebookSource(first: AnnotationSource, second: AnnotationSource): Boolean =
    first.kind == second.kind && first.sourceId == second.sourceId && first.accountId == second.accountId

/** Position updates are cheap and must never repeat full-library search/sort work. */
internal fun notebookWithPosition(
    filtered: Flow<AnnotationNotebookState>,
    listIndex: Flow<Int>,
    scrollOffset: Flow<Int>,
): Flow<AnnotationNotebookState> = combine(filtered, listIndex, scrollOffset) { page, index, offset ->
    page.copy(selection = page.selection.copy(listIndex = index.coerceAtLeast(0), scrollOffset = offset.coerceAtLeast(0)))
}
