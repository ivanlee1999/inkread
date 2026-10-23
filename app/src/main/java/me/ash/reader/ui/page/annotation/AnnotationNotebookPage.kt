package me.ash.reader.ui.page.annotation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.ash.reader.domain.annotation.Annotation

@Composable
fun AnnotationNotebookPage(
    onBack: () -> Unit,
    onOpen: (Annotation) -> Unit,
    viewModel: AnnotationNotebookViewModel = hiltViewModel(),
    actions: AnnotationReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    AnnotationNotebookScreen(state, onBack, onOpen, actions, viewModel::checkSource,
        viewModel::search, viewModel::notesOnly, viewModel::unsyncedOnly, viewModel::page, viewModel::position, searchText)
}

private class NotebookUiSession { var active = true }
private enum class NotebookDialog { DETAILS, NOTE, DELETE }

/** Production UI seam uses only local/fake annotation actions in native fixtures. */
@Composable
internal fun AnnotationNotebookScreen(
    state: AnnotationNotebookState,
    onBack: () -> Unit,
    onOpen: (Annotation) -> Unit,
    actions: AnnotationReaderActions,
    checkSource: suspend (Annotation) -> String?,
    onSearch: (String) -> Unit,
    onNotes: (Boolean) -> Unit,
    onUnsynced: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
    onPosition: (Int, Int) -> Unit,
    searchText: String = state.selection.search,
) {
    val session = remember { NotebookUiSession() }
    DisposableEffect(session) { onDispose { session.active = false } }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Annotation?>(null) }
    var dialog by remember { mutableStateOf<NotebookDialog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Observe the selected source independently of the filtered/page-bounded list. An export can
    // remove a row from Unsynced while its open details must still show the new live status.
    val selectedDocument by key(selected?.id) {
        remember(selected?.source) { actions.observe(selected?.source) }.collectAsState(AnnotationDocumentState())
    }
    val liveSelected = selectedDocument.annotations.firstOrNull { it.id == selected?.id }
    val selectedReady = !selectedDocument.loading && selectedDocument.error == null && liveSelected != null
    val close = { if (!busy) { selected = null; dialog = null; error = null } }
    val finish: (String?) -> Unit = { failure ->
        if (session.active) {
            busy = false; error = failure
            if (failure == null) { selected = null; dialog = null }
        }
    }
    val open: (Annotation) -> Unit = { annotation ->
        if (!busy) {
            busy = true; error = null
            scope.launch {
                val failure = checkSource(annotation)
                if (session.active) {
                    busy = false
                    if (failure == null) { selected = null; dialog = null; onOpen(annotation) }
                    else { selected = annotation; dialog = NotebookDialog.DETAILS; error = failure }
                }
            }
        }
    }
    Scaffold { padding ->
        key(state.loading, state.selection.search, state.selection.notesOnly, state.selection.unsyncedOnly, state.page) {
            val listState = rememberLazyListState(state.selection.listIndex, state.selection.scrollOffset)
            LaunchedEffect(listState, state.loading) {
                // A placeholder must not overwrite restored SavedState before records arrive.
                if (!state.loading) snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .distinctUntilChanged().collect { (index, offset) -> onPosition(index, offset) }
            }
            AnnotationNotebookContent(state = state, listState = listState,
                modifier = Modifier.padding(padding), busy = busy,
                onBack = onBack, onOpen = open, onOptions = { if (!busy) { selected = it; dialog = NotebookDialog.DETAILS; error = null } },
                onSearch = onSearch, onNotes = onNotes, onUnsynced = onUnsynced, onPage = onPage, searchText = searchText)
        }
    }
    selected?.let { snapshot ->
        val annotation = liveSelected ?: snapshot
        val removed = !selectedDocument.loading && selectedDocument.error == null && liveSelected == null
        val ready = !busy && selectedReady
        when (dialog) {
            NotebookDialog.DETAILS -> AnnotationDialog("Highlight", close, !busy, "notebook_details") {
                Text(annotation.source.title, fontWeight = FontWeight.Bold)
                Text(annotation.source.sourceName ?: annotation.source.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text(annotation.quote)
                if (annotation.note.isNotBlank()) Text(annotation.note)
                AnnotationStatus(annotationExportLabel(annotation))
                if (removed) AnnotationStatus("This highlight was deleted locally. This displayed quote is no longer saved.")
                if (selectedDocument.loading) AnnotationStatus("Checking highlight…")
                selectedDocument.error?.let { AnnotationStatus(it) }
                error?.let { AnnotationStatus(it) }
                AnnotationAction(if (busy) "Opening…" else "Open original passage", Modifier.testTag("notebook_open_original"), ready) { open(annotation) }
                AnnotationAction("Edit note", Modifier.testTag("notebook_edit_note"), ready) { selected = annotation; error = null; dialog = NotebookDialog.NOTE }
                if (annotation.canRetry && !removed) AnnotationAction("Retry Readwise export", Modifier.testTag("notebook_retry"), ready) {
                    busy = true; actions.retry(annotation.id, finish)
                }
                AnnotationAction("Delete locally", Modifier.testTag("notebook_delete"), ready) { error = null; dialog = NotebookDialog.DELETE }
                AnnotationAction("Close", enabled = !busy, onClick = close)
            }
            NotebookDialog.NOTE -> key(annotation.id) {
                AnnotationNoteEditor(snapshot.quote, snapshot.note, busy, error,
                    onSave = { note ->
                        if (!busy) {
                            if (selectedReady) { busy = true; actions.updateNote(snapshot.id, note, finish) }
                            else error = "This highlight is no longer available for editing. Your typed note has not been saved; it remains here until you close this editor."
                        }
                    }, onCancel = close)
            }
            NotebookDialog.DELETE -> AnnotationDialog("Delete local annotation?", close, !busy, "notebook_delete_confirmation") {
                Text(annotation.quote)
                Text("This removes the highlight and note from this device only. An exported copy stays in Readwise and must be deleted there separately.")
                error?.let { AnnotationStatus(it) }
                if (removed) AnnotationStatus("This highlight has already been deleted locally.")
                AnnotationAction(if (busy) "Deleting…" else "Delete locally", Modifier.testTag("notebook_confirm_delete"), ready) {
                    busy = true; actions.remove(annotation.id, finish)
                }
                AnnotationAction("Keep annotation", enabled = !busy, onClick = close)
            }
            null -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnnotationNotebookContent(
    state: AnnotationNotebookState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onBack: () -> Unit,
    onOpen: (Annotation) -> Unit,
    onOptions: (Annotation) -> Unit,
    onSearch: (String) -> Unit,
    onNotes: (Boolean) -> Unit,
    onUnsynced: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
    searchText: String = state.selection.search,
) {
    Column(modifier.fillMaxSize().testTag("annotation_notebook")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            NotebookButton("Back", "notebook_back", enabled = !busy, onClick = onBack)
            Text("Highlights", Modifier.weight(1f).padding(start = 16.dp).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        }
        BasicTextField(value = searchText, onValueChange = onSearch, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface).padding(12.dp)
                .testTag("notebook_search").semantics { contentDescription = "Search quotes, notes, articles and sources" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            decorationBox = { input -> Box { if (searchText.isEmpty()) Text("Search highlights", color = MaterialTheme.colorScheme.onSurfaceVariant); input() } })
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NotebookButton(if (state.selection.notesOnly) "✓ Notes" else "Notes", "notebook_filter_notes") { onNotes(!state.selection.notesOnly) }
            NotebookButton(if (state.selection.unsyncedOnly) "✓ Unsynced" else "Unsynced", "notebook_filter_unsynced") { onUnsynced(!state.selection.unsyncedOnly) }
            if (searchText.isNotEmpty()) NotebookButton("Clear search", "notebook_clear_search") { onSearch("") }
        }
        if (state.selection.unsyncedOnly) Text("Queued, sending, failed, paused or needs review. Intentionally local-only highlights are not included.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
        when {
            state.loading -> Text("Loading highlights…", Modifier.padding(20.dp))
            state.error != null -> Text(state.error, Modifier.padding(20.dp))
            state.items.isEmpty() -> Text(if (state.selection.search.isBlank() && !state.selection.notesOnly && !state.selection.unsyncedOnly)
                "No highlights yet. Select a passage in an article to save it here." else "No highlights match. Try another search or turn off a filter.", Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.weight(1f).testTag("notebook_list"), state = listState) {
                items(state.items, key = { it.id }) { annotation ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Column(Modifier.fillMaxWidth().testTag("notebook_item_${annotation.id}").clickable(enabled = !busy,
                            interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                            onClick = { onOpen(annotation) }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(annotation.source.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(annotation.source.sourceName ?: annotation.source.url.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(annotation.quote, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            if (annotation.note.isNotBlank()) Text(annotation.note, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(annotationExportLabel(annotation), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        NotebookButton("Note and actions", "notebook_actions_${annotation.id}", !busy) { onOptions(annotation) }
                    }
                    HorizontalDivider()
                }
            }
        }
        if (!state.loading && state.error == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NotebookButton("Previous", "notebook_previous", !busy && state.page > 0) { onPage(state.page - 1) }
                Text("${state.page + 1}/${state.pageCount} · ${state.total} highlights", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                NotebookButton("Next", "notebook_next", !busy && state.page + 1 < state.pageCount) { onPage(state.page + 1) }
            }
        }
    }
}

@Composable
private fun NotebookButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, Modifier.heightIn(min = 48.dp).testTag(tag).clickable(enabled = enabled,
        interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
        .padding(vertical = 12.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.bodyMedium)
}
