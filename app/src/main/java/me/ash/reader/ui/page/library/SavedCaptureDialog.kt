package me.ash.reader.ui.page.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import me.ash.reader.infrastructure.preference.LocalEInkMode

@Composable
internal fun SavedCaptureHost(
    viewModel: SavedCaptureViewModel,
    ready: Boolean,
    onSubscribe: (String) -> Unit,
    onSaved: (String) -> Unit,
    onOpenSaved: ((String) -> Unit)? = null,
) {
    val serialized by viewModel.queue.collectAsState()
    val request = remember(serialized) { viewModel.pending().firstOrNull() }
    val error by viewModel.error.collectAsState()
    val savedId by viewModel.savedItemId.collectAsState()
    val noticeId by viewModel.savedNoticeId.collectAsState()
    LaunchedEffect(ready, savedId) {
        if (ready) savedId?.let { onSaved(it); viewModel.acknowledgeSaved() }
    }
    LaunchedEffect(ready, request?.token) {
        if (ready && request?.mode == LinkCaptureMode.SUBSCRIBE.name && request.selectedUrl != null) {
            viewModel.subscribe(request.token)?.let(onSubscribe)
        }
    }
    if (!ready) return
    if (request != null) {
        SavedCaptureDialog(
            request = request,
            error = error,
            onSelect = { viewModel.select(request.token, it) },
            onSave = { viewModel.saveSelected(request.token) },
            onSubscribe = { viewModel.subscribe(request.token)?.let(onSubscribe) },
            onDismiss = { viewModel.dismiss(request.token) },
        )
    } else if (noticeId != null) {
        SavedLibraryDialog("Article saved", viewModel::dismissSavedNotice, tag = "saved-capture-success") {
            Text("The link is in your on-device library. If it was already saved, its location and reading position have been kept.")
            Text("Text and images download separately; offline status is shown on each article.")
            if (onOpenSaved != null) SavedLibraryAction("Open article") {
                val id = noticeId
                viewModel.dismissSavedNotice()
                if (id != null) onOpenSaved(id)
            }
            SavedLibraryAction("Done", onClick = viewModel::dismissSavedNotice)
        }
    } else if (error != null) {
        SavedLibraryDialog("Could not capture link", viewModel::clearError) {
            Text(error.orEmpty())
            SavedLibraryAction("Close", onClick = viewModel::clearError)
        }
    }
}

@Composable
internal fun SavedCaptureDialog(
    request: PendingLinkCapture,
    error: String?,
    onSelect: (String) -> Unit,
    onSave: () -> Unit,
    onSubscribe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val input = remember(request.text, request.titleHint) { parseSharedLinks(request.text, request.titleHint) }
    SavedLibraryDialog(
        title = if (request.mode == LinkCaptureMode.SAVE.name) "Save article" else "Open shared link",
        onDismiss = onDismiss,
        dismissEnabled = !request.saving,
        tag = "saved-capture-dialog",
    ) {
        input.titleHint?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        Text(
            when {
                input.urls.isEmpty() -> "No valid web link was found. Share or paste an http or https article link."
                input.requiresSelection -> "Choose one link to continue. Nothing is saved or subscribed until you choose an action."
                request.mode == LinkCaptureMode.SAVE.name -> "Save this article to your on-device library."
                else -> "Keep one article for later, or subscribe to updates from a feed."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(Modifier.selectableGroup()) {
            input.urls.forEachIndexed { index, url ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("saved-capture-url-$index")
                        .selectable(selected = request.selectedUrl == url, enabled = !request.saving,
                            role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = { onSelect(url) }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (input.requiresSelection) Text(if (request.selectedUrl == url) "●" else "○",
                        Modifier.padding(horizontal = 12.dp).clearAndSetSemantics { },
                        style = MaterialTheme.typography.titleLarge)
                    Text(url, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (request.saving) Text("Saving link…", modifier = Modifier.testTag("saved-capture-saving"))
        HorizontalDivider()
        if (request.mode != LinkCaptureMode.SUBSCRIBE.name) {
            SavedLibraryAction("Save article", Modifier.testTag("saved-capture-save"),
                enabled = request.selectedUrl != null && !request.saving, onClick = onSave)
        }
        if (request.mode != LinkCaptureMode.SAVE.name) {
            SavedLibraryAction("Subscribe to feed", Modifier.testTag("saved-capture-subscribe"),
                enabled = request.selectedUrl != null && !request.saving, onClick = onSubscribe)
        }
        SavedLibraryAction("Cancel", Modifier.testTag("saved-capture-cancel"), enabled = !request.saving, onClick = onDismiss)
    }
}

@Composable
internal fun SavedPasteDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    SavedLibraryDialog("Save a link", onDismiss, tag = "saved-paste-dialog") {
        Text("Paste an article link. Your library stays on this device and is separate from RSS accounts.")
        Text("Article link", style = MaterialTheme.typography.labelLarge)
        BasicTextField(value = text, onValueChange = { text = it.take(32_768) },
            modifier = Modifier.fillMaxWidth().testTag("saved-paste-input")
                .semantics { contentDescription = "Article link" }
                .border(1.dp, MaterialTheme.colorScheme.onSurface).padding(12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), minLines = 2, maxLines = 6)
        SavedLibraryAction("Continue", Modifier.testTag("saved-paste-continue"), enabled = text.isNotBlank()) { onSave(text) }
        SavedLibraryAction("Cancel", onClick = onDismiss)
    }
}

/** Static e-ink dialog, with scrollable actions for large accessibility fonts and short windows. */
@Composable
internal fun SavedLibraryDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    tag: String = "saved-library-dialog",
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = { if (dismissEnabled) onDismiss() }, properties = DialogProperties(
        dismissOnBackPress = dismissEnabled, dismissOnClickOutside = dismissEnabled,
    )) {
        // A capture can arrive while a reader is open. Own keys exclusively while this modal
        // is present; the existing WebView pager does not use the list's focus gate.
        val volumeOwner = remember { Any() }
        val einkMode = LocalEInkMode.current.isEInkMode()
        DisposableEffect(einkMode, volumeOwner) {
            if (einkMode) VolumeKeyEventBus.register(VolumeKeyPriority.HIGH, volumeOwner)
            onDispose { if (einkMode) VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH, volumeOwner) }
        }
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setWindowAnimations(0) }
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().testTag(tag)
                .background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.onSurface)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun SavedLibraryAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(label, modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
        enabled = enabled, role = Role.Button, indication = null,
        interactionSource = remember { MutableInteractionSource() }, onClick = onClick,
    ).padding(vertical = 12.dp, horizontal = 4.dp), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
}
