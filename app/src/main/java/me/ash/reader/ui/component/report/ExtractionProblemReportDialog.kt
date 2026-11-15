package me.ash.reader.ui.component.report

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.report.MAX_EXTRACTION_REPORT_NOTES_CHARS

internal enum class ExtractionReportAction { SAVE, COPY, SHARE }

/** The caller saves and validates the frozen article identity before copying or sharing. */
@Composable
internal fun ExtractionProblemReportDialog(
    notes: String,
    preview: String,
    busy: Boolean,
    error: String?,
    canRemove: Boolean,
    onNotesChange: (String) -> Unit,
    onAction: (ExtractionReportAction) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.extraction_report_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.extraction_report_description))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { text ->
                        val end = minOf(text.length, MAX_EXTRACTION_REPORT_NOTES_CHARS).let {
                            if (it > 0 && it < text.length && text[it - 1].isHighSurrogate() && text[it].isLowSurrogate()) it - 1 else it
                        }
                        onNotesChange(text.substring(0, end))
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("extraction_report_notes"),
                    label = { Text(stringResource(R.string.extraction_report_notes)) },
                    supportingText = { Text(stringResource(R.string.extraction_report_notes_hint)) },
                    minLines = 2,
                    maxLines = 4,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                TextButton(enabled = !busy, onClick = { onAction(ExtractionReportAction.SAVE) }) {
                    Text(stringResource(R.string.extraction_report_save))
                }
                if (canRemove) TextButton(enabled = !busy, onClick = onRemove) {
                    Text(stringResource(R.string.extraction_report_remove))
                }
                Text(stringResource(R.string.extraction_report_preview), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                SelectionContainer { Text(preview, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !busy, onClick = { onAction(ExtractionReportAction.SHARE) }) {
                    Text(stringResource(R.string.extraction_report_share))
                }
                TextButton(enabled = !busy, onClick = { onAction(ExtractionReportAction.COPY) }) {
                    Text(stringResource(R.string.extraction_report_copy))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Plain text only; opening a chooser never chooses a recipient or sends a message. */
internal fun extractionProblemShareIntent(report: String, title: String): Intent {
    require(report.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Report exceeds sharing limit" }
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, report)
    }
}
