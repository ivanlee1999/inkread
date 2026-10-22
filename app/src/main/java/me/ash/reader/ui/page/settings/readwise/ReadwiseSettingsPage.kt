package me.ash.reader.ui.page.settings.readwise

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.infrastructure.preference.OpenLinkPreference
import me.ash.reader.infrastructure.readwise.ReadwiseConnectionState
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.page.annotation.AnnotationAction
import me.ash.reader.ui.page.annotation.AnnotationStatus

@Composable
fun ReadwiseSettingsPage(onBack: () -> Unit, viewModel: ReadwiseSettingsViewModel = hiltViewModel()) {
    val connection by viewModel.connection.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val message by viewModel.message.collectAsState()
    val working by viewModel.working.collectAsState()
    val context = LocalContext.current
    ReadwiseSettingsContent(connection, queue, message, working, onBack,
        onOpenTokenPage = { context.openURL("https://readwise.io/access_token", OpenLinkPreference.AutoPreferDefaultBrowser) },
        onConnect = viewModel::connect, onExportEnabled = viewModel::setExportEnabled, onDisconnect = viewModel::disconnect)
}

/** Native token entry only. Tests can use this seam without a network or credential store. */
@Composable
internal fun ReadwiseSettingsContent(
    connection: ReadwiseConnectionState,
    queue: ReadwiseQueueSummary,
    message: String?,
    working: Boolean,
    onBack: () -> Unit,
    onOpenTokenPage: () -> Unit,
    onConnect: (String, Boolean) -> Unit,
    onExportEnabled: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var autoExport by remember { mutableStateOf(false) }
    val busy = working || connection.busy
    DisposableEffect(Unit) { onDispose { token = "" } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).testTag("readwise_settings"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnnotationAction("Back", Modifier.testTag("readwise_settings_back"), onClick = onBack)
        Text("Annotations & Readwise", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Highlights and notes are stored on your device first and work offline without a Readwise account. Removing an RSS or Saved article does not delete its annotations.")
        Text("Available in Saved articles and the e-ink RSS reader. Annotation selection is not available in the legacy RSS scrolling reader.")
        HorizontalDivider()
        AnnotationStatus(when {
            busy -> "Updating Readwise connection…"
            !connection.connected -> "Readwise: not connected"
            connection.exportEnabled -> "Readwise: connected · Auto-export on"
            else -> "Readwise: connected · Auto-export paused"
        })
        Text("Exports go to the Readwise highlights library, not to Reader as mirrored documents.")
        Text("When auto-export is on, new highlights send the selected quote, note, article title, highlighted time, and author and source URL when available to Readwise. The selection’s validated text offset is sent as ordering metadata, not as a Reader document anchor. Full selection anchors stay on your device. Exported note edits are sent to the same connection. Local saves do not wait for the network.")
        connection.error?.let { AnnotationStatus(it) }
        message?.let { AnnotationStatus(it) }
        if (connection.connected) {
            AnnotationAction(if (connection.exportEnabled) "Pause auto-export" else "Resume auto-export", Modifier.testTag("readwise_toggle_export"), !busy) {
                onExportEnabled(!connection.exportEnabled)
            }
            Text("Pausing or disconnecting does not remove anything from Readwise. Requests already sent cannot be recalled.")
            AnnotationAction("Disconnect and remove saved token", Modifier.testTag("readwise_disconnect"), !busy, onDisconnect)
        }
        HorizontalDivider()
        Text(if (connection.connected) "Replace connection" else "Connect Readwise", style = MaterialTheme.typography.titleLarge)
        AnnotationAction("Get your token at readwise.io/access_token", Modifier.testTag("readwise_token_link"), onClick = onOpenTokenPage)
        Text("Enter the token here in the installed app. It is masked, stored securely on this device after connection, and never sent to the article's web content.")
        BasicTextField(value = token, onValueChange = { token = it }, enabled = !busy, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).border(1.dp, MaterialTheme.colorScheme.onSurface)
                .padding(14.dp).testTag("readwise_token_input").semantics { contentDescription = "Readwise access token" })
        Text("Auto-export new annotations: ${if (autoExport) "On" else "Off"}", Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = autoExport, enabled = !busy, role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() }, indication = null, onValueChange = { autoExport = it })
            .padding(vertical = 12.dp).testTag("readwise_connect_auto_export"))
        Text("Connect sends this token only to Readwise for validation. Replacing or reconnecting creates a new connection: old queued, failed, local-only and previously exported highlights are not silently sent to it.")
        AnnotationAction(if (connection.connected) "Replace connection" else "Connect", Modifier.testTag("readwise_connect"), !busy && token.isNotBlank()) {
            val enteredToken = token
            token = ""
            onConnect(enteredToken, autoExport)
        }
        HorizontalDivider()
        Text("Annotation export status", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        queue.error?.let { AnnotationStatus(it) }
        AnnotationStatus("Queued: ${queue.queued} · Sending: ${queue.sending} · Exported: ${queue.exported}")
        Text("Local only: ${queue.localOnly} · Paused: ${queue.paused} · Failed: ${queue.failed} · Needs review: ${queue.reviewRequired}")
        Text("Offline and rate-limited exports retry automatically. Open an article's Annotations list for per-highlight status and safe retries. An uncertain server response is held for review, not blindly resent.")
        Text("Deleting locally leaves any Readwise copy intact. Delete that copy in Readwise if needed.", Modifier.padding(bottom = 24.dp))
    }
}
