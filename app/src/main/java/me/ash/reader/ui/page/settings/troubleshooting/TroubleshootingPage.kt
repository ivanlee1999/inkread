package me.ash.reader.ui.page.settings.troubleshooting

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ReportGmailerrorred
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.work.WorkInfo
import java.util.Date
import java.text.DateFormat as JavaDateFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.infrastructure.report.ExtractionProblemFormatter
import me.ash.reader.infrastructure.report.SavedExtractionProblem
import me.ash.reader.ui.component.report.ExtractionProblemReportDialog
import me.ash.reader.ui.component.report.ExtractionReportAction
import me.ash.reader.ui.component.report.extractionProblemShareIntent
import me.ash.reader.domain.data.Log
import me.ash.reader.domain.service.SyncWorker.Companion.ONETIME_WORK_TAG
import me.ash.reader.domain.service.SyncWorker.Companion.PERIODIC_WORK_TAG
import me.ash.reader.infrastructure.preference.OpenLinkPreference
import me.ash.reader.ui.component.base.Banner
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYDialog
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.ext.DateFormat
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.getCurrentVersion
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.toString
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TroubleshootingPage(onBack: () -> Unit, viewModel: TroubleshootingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val issueTracerUrl = stringResource(R.string.issue_tracer_url)
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val uiState = viewModel.troubleshootingUiState.collectAsStateValue()
    val reportState = viewModel.extractionReportState.collectAsStateValue()
    val reportAccountId = viewModel.extractionReportAccountId.collectAsStateValue()
    val reports = if (reportState.accountId == reportAccountId) reportState.reports else emptyList()
    val clipboard = LocalClipboard.current
    val reportTitleText = stringResource(R.string.extraction_report_title)
    val reportShareText = stringResource(R.string.extraction_report_share)
    val reportSavedText = stringResource(R.string.extraction_report_saved)
    val reportCopiedText = stringResource(R.string.extraction_report_copied)
    val reportRemovedText = stringResource(R.string.extraction_report_removed)
    val reportFailedText = stringResource(R.string.extraction_report_failed)
    val reportUnavailableText = stringResource(R.string.extraction_report_unavailable)
    var selectedReport by remember(reportAccountId) { mutableStateOf<SavedExtractionProblem?>(null) }
    var reportNotes by remember(reportAccountId) { mutableStateOf("") }
    var reportBusy by remember(reportAccountId) { mutableStateOf(false) }
    var reportError by remember(reportAccountId) { mutableStateOf<String?>(null) }
    var byteArray by remember { mutableStateOf(ByteArray(0)) }

    val syncLogList = remember { mutableStateListOf<Log>() }

    LaunchedEffect(viewModel) { viewModel.getSyncLogs().let { syncLogList.addAll(it) } }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MimeType.JSON)) {
            result ->
            viewModel.exportPreferencesAsJSON(context) { byteArray ->
                result?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(byteArray)
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let { uri ->
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    byteArray = inputStream.readBytes()
                    viewModel.tryImport(context, byteArray)
                }
            }
        }

    val onetimeWorkerInfos =
        viewModel.workManager
            .getWorkInfosByTagFlow(ONETIME_WORK_TAG)
            .collectAsStateValue(emptyList())

    val periodicWorkerInfos =
        viewModel.workManager
            .getWorkInfosByTagFlow(PERIODIC_WORK_TAG)
            .collectAsStateValue(emptyList())

    RYScaffold(
        containerColor =
            MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack,
            )
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.troubleshooting), desc = "")
                    Spacer(modifier = Modifier.height(16.dp))
                    Banner(
                        title = stringResource(R.string.bug_report),
                        icon = Icons.Outlined.Info,
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    ) {
                        context.openURL(
                            issueTracerUrl,
                            OpenLinkPreference.AutoPreferCustomTabs,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.app_preferences),
                    )
                    SettingItem(
                        title = stringResource(R.string.import_from_json),
                        onClick = { importLauncher.launch(arrayOf(MimeType.ANY)) },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.export_as_json),
                        onClick = { preferenceFileLauncher(context, exportLauncher) },
                    ) {}
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    Subtitle(modifier = Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.extraction_report_list_title))
                    Text(stringResource(R.string.extraction_report_list_description), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    if (reportState.loading || reportState.accountId != reportAccountId) {
                        Text(stringResource(R.string.extraction_report_loading), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    } else if (reportState.failed) {
                        Text(stringResource(R.string.extraction_report_load_failed), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    } else if (reports.isEmpty()) {
                        Text(stringResource(R.string.extraction_report_list_empty), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    }
                }
                items(reports, key = { "extraction-${it.id}" }) { report ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = !reportBusy) reportOpen@ {
                        if (reportBusy) return@reportOpen
                        reportBusy = true
                        scope.launch {
                            try {
                                val current = viewModel.currentExtractionReport(report)
                                if (viewModel.extractionReportAccountId.value == current.accountId) {
                                    selectedReport = current
                                    reportNotes = current.notes
                                    reportError = null
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { context.showToast(reportUnavailableText) }
                            finally { reportBusy = false }
                        }
                    }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Text(report.snapshot.publicArticleUrl ?: stringResource(R.string.extraction_report_private_label),
                            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(JavaDateFormat.getDateTimeInstance(JavaDateFormat.SHORT, JavaDateFormat.SHORT)
                            .format(Date(report.updatedAtEpochMillis)), style = MaterialTheme.typography.bodySmall)
                        if (report.notes.isNotBlank()) Text(report.notes, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                item {
                    Subtitle(modifier = Modifier.padding(horizontal = 24.dp), text = "Worker infos")
                }
                items(onetimeWorkerInfos) {
                    WorkInfo(
                        tags = it.tags,
                        state = it.state,
                        nextScheduledMillis = it.nextScheduleTimeMillis,
                    )
                }
                items(periodicWorkerInfos) {
                    WorkInfo(
                        tags = it.tags,
                        state = it.state,
                        nextScheduledMillis = it.nextScheduleTimeMillis,
                    )
                }
                if (syncLogList.isNotEmpty()) {
                    item {
                        Subtitle(
                            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 24.dp),
                            text = "Sync errors",
                        )
                    }
                    items(syncLogList) { SyncLogItem(log = it) }
                    item {
                        Button(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 24.dp),
                            onClick = {
                                viewModel.clearSyncLogs()
                                syncLogList.clear()
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(
                        modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                    )
                }
            }
        },
    )

    selectedReport?.let { report ->
        fun stillCurrent() = selectedReport === report && viewModel.extractionReportAccountId.value == report.accountId
        ExtractionProblemReportDialog(
            notes = reportNotes,
            preview = ExtractionProblemFormatter.markdown(report.snapshot, reportNotes),
            busy = reportBusy,
            error = reportError,
            canRemove = true,
            onNotesChange = { reportNotes = it },
            onDismiss = { selectedReport = null },
            onRemove = reportRemove@ {
                if (reportBusy) return@reportRemove
                reportBusy = true
                scope.launch {
                    try {
                        viewModel.removeExtractionReport(report)
                        if (stillCurrent()) {
                            selectedReport = null
                            context.showToast(reportRemovedText)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (stillCurrent()) reportError = reportFailedText }
                    finally { reportBusy = false }
                }
            },
            onAction = reportAction@ { action ->
                if (reportBusy) return@reportAction
                val notes = reportNotes
                reportBusy = true
                scope.launch {
                    try {
                        val saved = viewModel.saveExtractionReportNotes(report, notes)
                        if (stillCurrent()) {
                            val text = ExtractionProblemFormatter.markdown(saved)
                            when (action) {
                                ExtractionReportAction.COPY -> {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(reportTitleText, text)))
                                    context.showToast(reportCopiedText)
                                }
                                ExtractionReportAction.SHARE -> context.startActivity(Intent.createChooser(
                                    extractionProblemShareIntent(text, reportTitleText),
                                    reportShareText))
                                ExtractionReportAction.SAVE -> context.showToast(reportSavedText)
                            }
                            selectedReport = null
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (stillCurrent()) reportError = reportFailedText }
                    finally { reportBusy = false }
                }
            },
        )
    }
    RYDialog(
        visible = uiState.warningDialogVisible,
        onDismissRequest = { viewModel.hideWarningDialog() },
        icon = {
            Icon(
                imageVector = Icons.Outlined.ReportGmailerrorred,
                contentDescription = stringResource(R.string.import_from_json),
            )
        },
        title = { Text(text = stringResource(R.string.import_from_json)) },
        text = { Text(text = stringResource(R.string.invalid_json_file_warning)) },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.hideWarningDialog()
                    viewModel.importPreferencesFromJSON(context, byteArray)
                }
            ) {
                Text(text = stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.hideWarningDialog() }) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

private fun preferenceFileLauncher(
    context: Context,
    launcher: ManagedActivityResultLauncher<String, Uri?>,
) {
    launcher.launch(
        "Read-You-" +
            "${context.getCurrentVersion()}-settings-" +
            "${Date().toString(DateFormat.YYYY_MM_DD_DASH_HH_MM_SS_DASH)}.json"
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkInfo(
    tags: Set<String>,
    state: WorkInfo.State,
    nextScheduledMillis: Long,
    modifier: Modifier = Modifier,
) {
    val date = remember(nextScheduledMillis) { Date(nextScheduledMillis) }
    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(tags.toString(), style = MaterialTheme.typography.bodyLarge)
        Text(state.toString(), style = MaterialTheme.typography.bodySmall)
        if (tags.contains(PERIODIC_WORK_TAG) && state != WorkInfo.State.FAILED) {
            Text("Next scheduled time: $date", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SyncLogItem(log: Log, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText(log.fileName, log.content))
                        )
                    }
                }
                .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(log.fileName, style = MaterialTheme.typography.titleMedium)
        Text(
            log.content,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
