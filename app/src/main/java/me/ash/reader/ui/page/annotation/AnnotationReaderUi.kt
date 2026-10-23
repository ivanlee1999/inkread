package me.ash.reader.ui.page.annotation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.ash.reader.domain.annotation.Annotation
import me.ash.reader.domain.annotation.AnnotationDraft
import me.ash.reader.domain.annotation.AnnotationExportState
import me.ash.reader.domain.annotation.AnnotationSource
import me.ash.reader.ui.page.home.reading.EInkReaderVolumeReservation
import org.json.JSONArray
import org.json.JSONObject

/** One immutable callback bundle per rendered document generation. */
class AnnotationReaderUi internal constructor(
    val available: Boolean,
    val selectionMode: Boolean,
    val annotationEditing: Boolean,
    /** Read live state so a Source click cannot race an accepted save or modal entry. */
    val canReplaceDocument: () -> Boolean,
    val annotationsJson: String,
    val jumpRequest: Pair<String, Long>?,
    val onSelectionModeChange: (Boolean) -> Unit,
    val onCreateAnnotation: (String, Boolean) -> Unit,
    val onAnnotationLocationsChanged: (String) -> Unit,
    val onAnnotationsClick: () -> Unit,
    val onAnnotationClick: (String) -> Unit,
    val resizeRequest: Pair<String, Long>?,
    val onResizeResult: (String, Boolean) -> Unit,
    val onResizeSelection: (String, String) -> Unit,
    val autoHighlightEnabled: Boolean,
    val onAutoCreateAnnotation: (String) -> Unit,
    val undoAutoHighlight: (() -> Unit)?,
    val status: String?,
    val onAnnotationJumpResult: (String, Boolean) -> Unit,
    internal val document: AnnotationDocumentState,
    internal val dialog: AnnotationDialogState?,
    internal val busy: Boolean,
    internal val error: String?,
    internal val notice: String?,
    internal val locations: Map<String, Boolean>,
    internal val close: () -> Unit,
    internal val edit: (Annotation) -> Unit,
    internal val resize: (Annotation) -> Unit,
    internal val delete: (Annotation) -> Unit,
    internal val saveNote: (String) -> Unit,
    internal val confirmDelete: () -> Unit,
    internal val jump: (Annotation) -> Unit,
    internal val retry: (Annotation) -> Unit,
    internal val dismissNotice: () -> Unit,
)

private class AnnotationUiSession {
    @Volatile var active = true
}

internal sealed interface AnnotationDialogState {
    data object List : AnnotationDialogState
    data class Detail(val id: String) : AnnotationDialogState
    data class Note(val draft: AnnotationDraft? = null, val existing: Annotation? = null, val existingId: String? = null, val originalQuote: String? = null) : AnnotationDialogState
    data class Delete(val annotation: Annotation) : AnnotationDialogState
}

@Composable
fun rememberAnnotationReaderUi(
    source: AnnotationSource?,
    documentRevision: Any?,
    viewModel: AnnotationReaderActions,
    requestedAnnotationId: String? = null,
    onDocumentInteractionStarted: (() -> Unit)? = null,
): AnnotationReaderUi {
    // Loading/error and any content replacement revoke all previous selection callbacks.
    val session = remember(source, documentRevision) { AnnotationUiSession() }
    DisposableEffect(session) { onDispose { session.active = false } }
    val currentSession by rememberUpdatedState(session)
    val currentOnDocumentInteractionStarted by rememberUpdatedState(onDocumentInteractionStarted)
    val document by key(source) {
        remember(source) { viewModel.observe(source) }.collectAsState(AnnotationDocumentState())
    }
    var selectionMode by remember(session) { mutableStateOf(false) }
    var dialog by remember(session) { mutableStateOf<AnnotationDialogState?>(null) }
    var busy by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var notice by remember(session) { mutableStateOf<String?>(null) }
    var locations by remember(session) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var jumpRequest by remember(session) { mutableStateOf<Pair<String, Long>?>(null) }
    var jumpSerial by remember(session) { mutableStateOf(0L) }
    var resizeRequest by remember(session) { mutableStateOf<Pair<String, Long>?>(null) }
    var pendingResizeRequest by remember(session) { mutableStateOf<Pair<String, Long>?>(null) }
    var resizeSerial by remember(session) { mutableStateOf(0L) }
    var resizingId by remember(session) { mutableStateOf<String?>(null) }
    var undoId by remember(session) { mutableStateOf<String?>(null) }
    var automaticAnchor by remember(session) { mutableStateOf<String?>(null) }
    var automaticObserved by remember(session) { mutableStateOf(false) }
    var automaticSaving by remember(session) { mutableStateOf(false) }
    var deferredManual by remember(session) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var feedback by remember(session) { mutableStateOf<String?>(null) }
    val autoHighlightEnabled by remember(viewModel) { viewModel.autoHighlight }.collectAsState(false)
    var handledJump by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedAnnotationId, document.loading, document.annotations, session) {
        val id = requestedAnnotationId
        if (id != null && source != null && !document.loading && document.error == null && handledJump != id) {
            handledJump = id
            if (document.annotations.any { it.id == id }) { jumpSerial += 1; jumpRequest = id to jumpSerial }
            else notice = "This highlight is no longer available in this article."
        }
    }
    val undoAction: (() -> Unit)? = undoId?.let { id ->
        {
            if (!busy && currentSession === session && session.active) {
                busy = true
                viewModel.remove(id) { failure ->
                    if (currentSession === session && session.active) {
                        busy = false; error = failure
                        if (failure == null) {
                            undoId = null; automaticAnchor = null; automaticObserved = false
                            feedback = "Highlight undone locally. A Readwise copy, if already sent, remains."
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(undoId, document.annotations, document.loading) {
        val id = undoId
        if (id != null && !document.loading) {
            val observed = document.annotations.firstOrNull { it.id == id }
            if (observed != null) { automaticObserved = true; automaticAnchor = observed.anchorJson }
            else if (automaticObserved) { undoId = null; automaticAnchor = null; automaticObserved = false }
        }
    }
    val status = listOfNotNull(feedback, annotationReaderStatus(document.annotations)).joinToString("\n").ifBlank { null }
    val annotationsJson = remember(document.annotations) {
        JSONArray().apply {
            document.annotations.forEach { annotation ->
                // Unparseable old anchors remain visible in the local list, never invented.
                runCatching { JSONObject(annotation.anchorJson) }.getOrNull()?.let { anchor ->
                    put(JSONObject().put("id", annotation.id).put("anchor", anchor))
                }
            }
        }.toString()
    }
    val result: (String?) -> Unit = { failure ->
        if (currentSession === session && session.active) {
            busy = false
            error = failure
            if (failure == null) { dialog = null; notice = null }
        }
    }
    val ui = AnnotationReaderUi(
        available = source != null && !document.loading && document.error == null,
        selectionMode = selectionMode,
        annotationEditing = dialog != null,
        canReplaceDocument = {
            currentSession === session && session.active && !busy && dialog == null &&
                !selectionMode && pendingResizeRequest == null && deferredManual == null
        },
        annotationsJson = annotationsJson,
        jumpRequest = jumpRequest,
        onSelectionModeChange = { if (currentSession === session && session.active && source != null) {
            // The reader sets native ownership before this synchronous acknowledgment. Keep
            // resizingId for save validation, but do not let it block Source after Back/Copy.
            if (it) pendingResizeRequest = null
            selectionMode = it
        } },
        onCreateAnnotation = create@{ json, addNote ->
            if ((currentSession !== session || !session.active) || source == null || document.loading || document.error != null) return@create
            if (busy) {
                // A native explicit click while the accepted automatic save is committing
                // must not vanish after the WebView closes its selection toolbar.
                if (automaticSaving && deferredManual == null) {
                    currentOnDocumentInteractionStarted?.invoke()
                    deferredManual = json to addNote
                }
                return@create
            }
            val selection = runCatching { JSONObject(json) }.getOrNull()
            if (selection?.optString("error") == "selection_too_large") {
                selectionMode = false
                error = null
                notice = "Selection is too large. Select a shorter passage."
                return@create
            }
            val quote = runCatching { selection?.getString("quote") }.getOrNull()
            if (quote.isNullOrBlank()) { notice = "Select article text before adding a highlight."; return@create }
            currentOnDocumentInteractionStarted?.invoke()
            val automaticId = undoId.takeIf { sameAnnotationSelection(automaticAnchor, json) }
            if (automaticId != null) {
                selectionMode = false
                if (addNote) dialog = AnnotationDialogState.Note(
                    existing = document.annotations.firstOrNull { it.id == automaticId },
                    existingId = automaticId, originalQuote = quote)
                return@create
            }
            feedback = null
            val draft = AnnotationDraft(source, quote, json)
            selectionMode = false
            error = null
            if (addNote) dialog = AnnotationDialogState.Note(draft = draft)
            else { busy = true; viewModel.create(draft, result) }
        },
        onAnnotationLocationsChanged = { json ->
            if (currentSession === session && session.active) {
                locations = runCatching {
                    val entries = JSONArray(json)
                    buildMap { for (index in 0 until entries.length()) {
                        val entry = entries.getJSONObject(index)
                        put(entry.getString("id"), entry.optString("status") == "attached")
                    } }
                }.getOrDefault(emptyMap())
            }
        },
        autoHighlightEnabled = autoHighlightEnabled,
        undoAutoHighlight = undoAction?.takeUnless { busy },
        status = status,
        resizeRequest = resizeRequest,
        onAnnotationClick = { id ->
            if (currentSession === session && session.active && !busy && document.annotations.any { it.id == id }) {
                currentOnDocumentInteractionStarted?.invoke()
                selectionMode = false; error = null; dialog = AnnotationDialogState.Detail(id)
            }
        },
        onResizeResult = { id, attached ->
            if (currentSession === session && session.active && pendingResizeRequest?.first == id) {
                pendingResizeRequest = null
            }
            if (currentSession === session && session.active && resizingId == id && !attached) {
                resizingId = null
                notice = "This highlight could not be located. Its quote and note are unchanged."
            }
        },
        onResizeSelection = resize@{ id, json ->
            if (currentSession !== session || !session.active || busy || resizingId != id) return@resize
            val quote = runCatching { JSONObject(json).getString("quote") }.getOrNull()
            if (quote.isNullOrBlank()) { notice = "Select a passage before saving the resized highlight."; return@resize }
            currentOnDocumentInteractionStarted?.invoke()
            busy = true; resizingId = null; pendingResizeRequest = null
            viewModel.updateSelection(id, quote, json) { failure ->
                result(failure)
                if (currentSession === session && session.active && failure == null) {
                    feedback = null
                    if (undoId == id) automaticAnchor = json
                }
            }
        },
        onAutoCreateAnnotation = automatic@{ json ->
            if (currentSession !== session || !session.active || source == null || busy || !autoHighlightEnabled ||
                document.loading || document.error != null || dialog != null) return@automatic
            val quote = runCatching { JSONObject(json).getString("quote") }.getOrNull()
            if (quote.isNullOrBlank()) return@automatic
            if ((undoId != null && sameAnnotationSelection(automaticAnchor, json)) ||
                document.annotations.any { sameAnnotationSelection(it.anchorJson, json) }) return@automatic
            currentOnDocumentInteractionStarted?.invoke()
            busy = true
            automaticSaving = true
            viewModel.createAutomatic(AnnotationDraft(source, quote, json)) { id, failure ->
                if (currentSession === session && session.active) {
                    busy = false; automaticSaving = false; error = failure
                    if (failure == null && id != null) { undoId = id; automaticAnchor = json; automaticObserved = false; feedback = null }
                }
            }
        },
        onAnnotationsClick = { if (currentSession === session && session.active && !busy) {
            currentOnDocumentInteractionStarted?.invoke()
            selectionMode = false; error = null; notice = null; dialog = AnnotationDialogState.List
        } },
        onAnnotationJumpResult = { id, attached -> if (currentSession === session && session.active && jumpRequest?.first == id && !attached) notice = "This highlight could not be located in the current text. Its quote and note are still saved." },
        document = document,
        dialog = dialog,
        busy = busy,
        error = error,
        notice = notice,
        locations = locations,
        close = { if (!busy) { dialog = null; error = null; notice = null } },
        resize = { annotation ->
            if (!busy && currentSession === session && session.active) {
                // Pin before publishing the resize request, not in its later Compose effect.
                currentOnDocumentInteractionStarted?.invoke()
                dialog = null; selectionMode = false; error = null
                resizingId = annotation.id; resizeSerial += 1
                resizeRequest = annotation.id to resizeSerial
                pendingResizeRequest = resizeRequest
            }
        },
        edit = { if (!busy && currentSession === session && session.active) {
            currentOnDocumentInteractionStarted?.invoke()
            dialog = AnnotationDialogState.Note(existing = it); error = null
        } },
        delete = { if (!busy && currentSession === session && session.active) {
            currentOnDocumentInteractionStarted?.invoke()
            dialog = AnnotationDialogState.Delete(it); error = null
        } },
        saveNote = { note ->
            if (!busy && currentSession === session && session.active) {
                val editor = dialog as? AnnotationDialogState.Note
                if (editor != null) {
                    currentOnDocumentInteractionStarted?.invoke()
                    busy = true
                    editor.draft?.let { viewModel.create(it.copy(note = note), result) }
                        ?: (editor.existing?.id ?: editor.existingId)?.let { viewModel.updateNote(it, note, result) }
                }
            }
        },
        confirmDelete = {
            val pending = dialog as? AnnotationDialogState.Delete
            if (!busy && currentSession === session && session.active && pending != null) {
                busy = true
                viewModel.remove(pending.annotation.id) { failure ->
                    if (currentSession === session && session.active) {
                        busy = false; error = failure
                        if (failure == null) {
                            if (undoId == pending.annotation.id) { undoId = null; automaticAnchor = null; automaticObserved = false }
                            dialog = AnnotationDialogState.List; notice = "Deleted from this device. Any Readwise copy remains."
                        }
                    }
                }
            }
        },
        jump = { annotation -> if (!busy && currentSession === session && session.active) { dialog = null; jumpSerial += 1; jumpRequest = annotation.id to jumpSerial } },
        retry = { annotation ->
            if (!busy && annotation.canRetry && currentSession === session && session.active) {
                busy = true
                viewModel.retry(annotation.id) { failure ->
                    if (currentSession === session && session.active) { busy = false; error = failure }
                }
            }
        },
        dismissNotice = { notice = null; error = null },
    )
    LaunchedEffect(busy, deferredManual, session) {
        val pending = deferredManual
        if (!busy && pending != null) {
            deferredManual = null
            if (currentSession === session && session.active) ui.onCreateAnnotation(pending.first, pending.second)
        }
    }
    return ui
}

/** Compose after the reader; the dialog owns modal Back, the engine owns selection Back. */
@Composable
fun AnnotationReaderDialogs(ui: AnnotationReaderUi) {
    when (val dialog = ui.dialog) {
        null -> if (ui.notice != null || ui.error != null) {
            AnnotationDialog("Annotations", ui.dismissNotice, tag = "annotation_notice") {
                AnnotationStatus(ui.error ?: ui.notice.orEmpty())
                AnnotationAction("Done", onClick = ui.dismissNotice)
            }
        }
        AnnotationDialogState.List -> AnnotationDialog("Annotations", ui.close, dismissEnabled = !ui.busy, tag = "annotation_list") {
            Text("Highlights and notes stay on this device, even if the article is removed. Readwise export is optional in Settings.")
            ui.document.error?.let { AnnotationStatus(it) }
            ui.error?.let { AnnotationStatus(it) }
            ui.notice?.let { AnnotationStatus(it) }
            ui.undoAutoHighlight?.let { undo -> AnnotationAction("Undo last automatic highlight", onClick = undo) }
            if (ui.document.loading) AnnotationStatus("Loading annotations…")
            else if (ui.document.annotations.isEmpty()) Text("No annotations yet. Close this list and long-press article text, then choose Highlight or Add note.")
            ui.document.annotations.forEach { annotation ->
                HorizontalDivider()
                Text(annotation.quote, fontWeight = FontWeight.Medium)
                if (annotation.note.isNotBlank()) Text(annotation.note)
                AnnotationStatus(annotationExportLabel(annotation))
                if (annotation.canRetry) AnnotationAction("Retry Readwise export", Modifier.testTag("annotation_retry_${annotation.id}"), !ui.busy) { ui.retry(annotation) }
                val attached = ui.locations[annotation.id]
                if (attached == false) Text("Not located in the current text. Your quote and note are preserved.")
                AnnotationAction("Go to highlight", Modifier.testTag("annotation_jump_${annotation.id}"), enabled = !ui.busy && attached != false) { ui.jump(annotation) }
                AnnotationAction("Resize highlight", Modifier.testTag("annotation_resize_${annotation.id}"), !ui.busy && attached != false) { ui.resize(annotation) }
                AnnotationAction("Edit note", Modifier.testTag("annotation_edit_${annotation.id}"), !ui.busy) { ui.edit(annotation) }
                AnnotationAction("Delete locally", Modifier.testTag("annotation_delete_${annotation.id}"), !ui.busy) { ui.delete(annotation) }
            }
            AnnotationAction("Close", onClick = ui.close)
        }
        is AnnotationDialogState.Detail -> AnnotationDialog("Highlight", ui.close, !ui.busy, "annotation_detail") {
            val annotation = ui.document.annotations.firstOrNull { it.id == dialog.id }
            if (annotation == null) Text("This highlight is no longer available.")
            else {
                Text(annotation.quote, fontWeight = FontWeight.Medium)
                if (annotation.note.isNotBlank()) Text(annotation.note)
                AnnotationStatus(annotationExportLabel(annotation))
                ui.error?.let { AnnotationStatus(it) }
                AnnotationAction("Edit note", Modifier.testTag("annotation_edit_${annotation.id}"), !ui.busy) { ui.edit(annotation) }
                AnnotationAction("Resize highlight", Modifier.testTag("annotation_resize_${annotation.id}"), !ui.busy) { ui.resize(annotation) }
                AnnotationAction("Delete locally", Modifier.testTag("annotation_delete_${annotation.id}"), !ui.busy) { ui.delete(annotation) }
                if (annotation.canRetry) AnnotationAction("Retry Readwise export", enabled = !ui.busy) { ui.retry(annotation) }
            }
            AnnotationAction("Close", onClick = ui.close)
        }
        is AnnotationDialogState.Note -> AnnotationNoteEditor(
            quote = dialog.draft?.quote ?: dialog.existing?.quote ?: dialog.originalQuote.orEmpty(),
            initialNote = dialog.existing?.note ?: dialog.draft?.note.orEmpty(),
            busy = ui.busy, error = ui.error, onSave = ui.saveNote, onCancel = ui.close,
        )
        is AnnotationDialogState.Delete -> AnnotationDialog("Delete local annotation?", ui.close, !ui.busy, "annotation_delete_confirmation") {
            Text(dialog.annotation.quote)
            Text("This removes the highlight and note from this device only. An exported copy stays in Readwise and must be deleted there separately.")
            ui.error?.let { AnnotationStatus(it) }
            AnnotationAction(if (ui.busy) "Deleting…" else "Delete locally", enabled = !ui.busy, onClick = ui.confirmDelete)
            AnnotationAction("Keep annotation", enabled = !ui.busy, onClick = ui.close)
        }
    }
}

@Composable
internal fun AnnotationNoteEditor(quote: String, initialNote: String, busy: Boolean, error: String?, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var note by remember(quote, initialNote) { mutableStateOf(initialNote) }
    AnnotationDialog("Annotation note", onCancel, !busy, "annotation_note_editor") {
        Text(quote)
        Text("Note (optional)")
        BasicTextField(
            value = note, onValueChange = { if (!busy) note = it }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).border(1.dp, MaterialTheme.colorScheme.onSurface)
                .padding(12.dp).testTag("annotation_note_input").semantics { contentDescription = "Annotation note" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Text("Saved locally first. New highlights export only if auto-export is on. Existing notes export only to their original active connection.")
        error?.let { AnnotationStatus(it) }
        AnnotationAction(if (busy) "Saving…" else "Save", Modifier.testTag("annotation_note_save"), !busy) { onSave(note) }
        AnnotationAction("Cancel", Modifier.testTag("annotation_note_cancel"), !busy, onCancel)
    }
}

@Composable
internal fun AnnotationDialog(title: String, onDismiss: () -> Unit, dismissEnabled: Boolean = true, tag: String = "annotation_dialog", content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (dismissEnabled) onDismiss() }, properties = DialogProperties(dismissOnBackPress = dismissEnabled, dismissOnClickOutside = false)) {
        val owner = remember { Any() }
        EInkReaderVolumeReservation(true, owner)
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setWindowAnimations(0) }
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().testTag(tag).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.onSurface).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun AnnotationAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button,
        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f), style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun AnnotationStatus(text: String) {
    Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
}

internal fun annotationExportLabel(annotation: Annotation): String = when (annotation.exportState) {
    AnnotationExportState.LOCAL_ONLY -> "Saved locally · Not exported"
    AnnotationExportState.QUEUED -> "Saved locally · Queued for Readwise"
    AnnotationExportState.SENDING -> "Saved locally · Sending to Readwise"
    AnnotationExportState.EXPORTED -> "Saved locally · Exported to Readwise"
    AnnotationExportState.FAILED -> "Saved locally · ${annotation.exportError ?: "Readwise export failed"}"
    AnnotationExportState.REVIEW_REQUIRED -> "Saved locally · ${annotation.exportError ?: "Check Readwise before retrying; server outcome is uncertain"}"
    AnnotationExportState.PAUSED -> "Saved locally · Readwise export paused"
}

/** Quote text alone is not identity: repeated passages at distinct offsets stay distinct. */
internal fun sameAnnotationSelection(first: String?, second: String): Boolean = runCatching {
    if (first == null) return@runCatching false
    val a = JSONObject(first)
    val b = JSONObject(second)
    listOf("version", "textMapVersion", "documentFingerprint", "start", "end", "quote", "rangeQuote").all { key ->
        a.has(key) == b.has(key) && (!a.has(key) || a.get(key) == b.get(key))
    } && a.has("start") && a.has("end") && a.has("quote")
}.getOrDefault(false)

/** All highlights in this article contribute; the newest success never hides a failed export. */
internal fun annotationReaderStatus(annotations: List<Annotation>): String? {
    if (annotations.isEmpty()) return null
    val counts = annotations.groupingBy { it.exportState }.eachCount()
    val parts = listOf(
        AnnotationExportState.REVIEW_REQUIRED to "need review",
        AnnotationExportState.FAILED to "failed",
        AnnotationExportState.PAUSED to "paused",
        AnnotationExportState.SENDING to "sending",
        AnnotationExportState.QUEUED to "queued",
        AnnotationExportState.EXPORTED to "exported",
        AnnotationExportState.LOCAL_ONLY to "local only",
    ).mapNotNull { (state, label) -> counts[state]?.takeIf { it > 0 }?.let { "$it $label" } }
    return "Saved locally: ${annotations.size} · Readwise: ${parts.joinToString(" · ")}"
}
