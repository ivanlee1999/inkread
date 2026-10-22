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
    val annotationsJson: String,
    val jumpRequest: Pair<String, Long>?,
    val onSelectionModeChange: (Boolean) -> Unit,
    val onCreateAnnotation: (String, Boolean) -> Unit,
    val onAnnotationLocationsChanged: (String) -> Unit,
    val onAnnotationsClick: () -> Unit,
    val onAnnotationJumpResult: (String, Boolean) -> Unit,
    internal val document: AnnotationDocumentState,
    internal val dialog: AnnotationDialogState?,
    internal val busy: Boolean,
    internal val error: String?,
    internal val notice: String?,
    internal val locations: Map<String, Boolean>,
    internal val close: () -> Unit,
    internal val edit: (Annotation) -> Unit,
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
    data class Note(val draft: AnnotationDraft? = null, val existing: Annotation? = null) : AnnotationDialogState
    data class Delete(val annotation: Annotation) : AnnotationDialogState
}

@Composable
fun rememberAnnotationReaderUi(
    source: AnnotationSource?,
    documentRevision: Any?,
    viewModel: AnnotationReaderActions,
): AnnotationReaderUi {
    // Loading/error and any content replacement revoke all previous selection callbacks.
    val session = remember(source, documentRevision) { AnnotationUiSession() }
    DisposableEffect(session) { onDispose { session.active = false } }
    val currentSession by rememberUpdatedState(session)
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
    return AnnotationReaderUi(
        available = source != null && !document.loading && document.error == null,
        selectionMode = selectionMode,
        annotationEditing = dialog != null,
        annotationsJson = annotationsJson,
        jumpRequest = jumpRequest,
        onSelectionModeChange = { if (currentSession === session && session.active && source != null) selectionMode = it },
        onCreateAnnotation = create@{ json, addNote ->
            if ((currentSession !== session || !session.active) || source == null || busy || document.loading || document.error != null) return@create
            val selection = runCatching { JSONObject(json) }.getOrNull()
            if (selection?.optString("error") == "selection_too_large") {
                selectionMode = false
                error = null
                notice = "Selection is too large. Select a shorter passage."
                return@create
            }
            val quote = runCatching { selection?.getString("quote") }.getOrNull()
            if (quote.isNullOrBlank()) { notice = "Select article text before adding a highlight."; return@create }
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
        onAnnotationsClick = { if (currentSession === session && session.active && !busy) { selectionMode = false; error = null; notice = null; dialog = AnnotationDialogState.List } },
        onAnnotationJumpResult = { id, attached -> if (currentSession === session && session.active && jumpRequest?.first == id && !attached) notice = "This highlight could not be located in the current text. Its quote and note are still saved." },
        document = document,
        dialog = dialog,
        busy = busy,
        error = error,
        notice = notice,
        locations = locations,
        close = { if (!busy) { dialog = null; error = null; notice = null } },
        edit = { if (!busy && currentSession === session && session.active) { dialog = AnnotationDialogState.Note(existing = it); error = null } },
        delete = { if (!busy && currentSession === session && session.active) { dialog = AnnotationDialogState.Delete(it); error = null } },
        saveNote = { note ->
            if (!busy && currentSession === session && session.active) {
                val editor = dialog as? AnnotationDialogState.Note
                if (editor != null) {
                    busy = true
                    editor.draft?.let { viewModel.create(it.copy(note = note), result) }
                        ?: editor.existing?.let { viewModel.updateNote(it.id, note, result) }
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
                        if (failure == null) { dialog = AnnotationDialogState.List; notice = "Deleted from this device. Any Readwise copy remains." }
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
            if (ui.document.loading) AnnotationStatus("Loading annotations…")
            else if (ui.document.annotations.isEmpty()) Text("No annotations yet. Close this list, choose Select text, then select a passage.")
            ui.document.annotations.forEach { annotation ->
                HorizontalDivider()
                Text(annotation.quote, fontWeight = FontWeight.Medium)
                if (annotation.note.isNotBlank()) Text(annotation.note)
                AnnotationStatus(annotationExportLabel(annotation))
                if (annotation.canRetry) AnnotationAction("Retry Readwise export", Modifier.testTag("annotation_retry_${annotation.id}"), !ui.busy) { ui.retry(annotation) }
                val attached = ui.locations[annotation.id]
                if (attached == false) Text("Not located in the current text. Your quote and note are preserved.")
                AnnotationAction("Go to highlight", Modifier.testTag("annotation_jump_${annotation.id}"), enabled = !ui.busy && attached != false) { ui.jump(annotation) }
                AnnotationAction("Edit note", Modifier.testTag("annotation_edit_${annotation.id}"), !ui.busy) { ui.edit(annotation) }
                AnnotationAction("Delete locally", Modifier.testTag("annotation_delete_${annotation.id}"), !ui.busy) { ui.delete(annotation) }
            }
            AnnotationAction("Close", onClick = ui.close)
        }
        is AnnotationDialogState.Note -> AnnotationNoteEditor(
            quote = dialog.draft?.quote ?: dialog.existing?.quote.orEmpty(),
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
