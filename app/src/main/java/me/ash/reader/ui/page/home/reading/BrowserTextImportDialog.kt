package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.rss.BrowserTextImport

/** No clipboard reads: the user pastes and reviews text using the platform text field. */
@Composable
internal fun BrowserTextImportDialog(
    onDismiss: () -> Unit,
    onApply: (String) -> Boolean,
) {
    // Deliberately not saveable: do not restore a preview without its live ownership guard.
    var draft by remember { mutableStateOf("") }
    var rejectedOversizePaste by remember { mutableStateOf(false) }
    var replacementBlocked by remember { mutableStateOf(false) }
    val validation = remember(draft) { BrowserTextImport.validate(draft) }
    val error = when {
        rejectedOversizePaste -> R.string.reader_browser_text_too_large
        replacementBlocked -> R.string.reader_browser_text_replace_blocked
        validation == BrowserTextImport.ValidationError.URL_ONLY -> R.string.reader_browser_text_url_only
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_browser_text_import)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.reader_browser_text_instructions))
                Text(stringResource(R.string.reader_browser_text_limitations))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { candidate ->
                        replacementBlocked = false
                        rejectedOversizePaste = BrowserTextImport.validate(candidate) ==
                            BrowserTextImport.ValidationError.TOO_LARGE
                        // Reject the entire oversized edit; never silently import truncated text.
                        if (!rejectedOversizePaste) draft = candidate
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .testTag("browser_text_preview"),
                    label = { Text(stringResource(R.string.reader_browser_text_preview)) },
                    minLines = 5,
                    maxLines = 12,
                    isError = error != null,
                )
                Text(stringResource(R.string.reader_browser_text_bound))
                if (error != null) {
                    Text(stringResource(error), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("browser_text_error"))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Revalidate at the action; parent must also recheck live document ownership.
                    if (BrowserTextImport.validate(draft) == null && !rejectedOversizePaste) {
                        replacementBlocked = !onApply(draft)
                    }
                },
                enabled = validation == null && !rejectedOversizePaste,
                modifier = Modifier.testTag("browser_text_apply"),
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("browser_text_cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
