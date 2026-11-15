package me.ash.reader.ui.page.settings.troubleshooting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.Log
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.report.ExtractionProblemReportStore
import me.ash.reader.infrastructure.report.SavedExtractionProblem
import me.ash.reader.ui.ext.fromDataStoreToJSONString
import me.ash.reader.ui.ext.fromJSONStringToDataStore

@HiltViewModel
class TroubleshootingViewModel
@Inject
constructor(
    private val accountService: AccountService,
    private val rssService: RssService,
    private val opmlService: OpmlService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    val workManager: WorkManager,
    private val syncLogger: SyncLogger,
    private val extractionReports: ExtractionProblemReportStore,
) : ViewModel() {

    private val _troubleshootingUiState = MutableStateFlow(TroubleshootingUiState())
    val troubleshootingUiState: StateFlow<TroubleshootingUiState> =
        _troubleshootingUiState.asStateFlow()
    val extractionReportAccountId = accountService.currentAccountIdFlow
    private val _extractionReportState = MutableStateFlow(ExtractionReportsUiState())
    val extractionReportState = _extractionReportState.asStateFlow()

    init {
        viewModelScope.launch {
            accountService.currentAccountIdFlow.collectLatest { accountId ->
                _extractionReportState.value = ExtractionReportsUiState(accountId = accountId, loading = accountId != null)
                if (accountId != null) try {
                    extractionReports.observe(accountId).collect { reports ->
                        if (accountService.currentAccountIdFlow.value == accountId) {
                            _extractionReportState.value = ExtractionReportsUiState(accountId, reports)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (accountService.currentAccountIdFlow.value == accountId) {
                        _extractionReportState.value = ExtractionReportsUiState(accountId = accountId, failed = true)
                    }
                }
            }
        }
    }

    /** A stale settings/dialog callback cannot read or export another account's report. */
    suspend fun currentExtractionReport(report: SavedExtractionProblem): SavedExtractionProblem {
        check(accountService.currentAccountIdFlow.value == report.accountId) { "Report account changed" }
        val current = extractionReports.find(report.accountId, report.articleId)
        check(current?.id == report.id && accountService.currentAccountIdFlow.value == report.accountId) { "Report unavailable" }
        return checkNotNull(current)
    }

    suspend fun saveExtractionReportNotes(report: SavedExtractionProblem, notes: String): SavedExtractionProblem {
        currentExtractionReport(report)
        val saved = extractionReports.updateNotes(report.accountId, report.id, notes)
        check(accountService.currentAccountIdFlow.value == report.accountId) { "Report account changed" }
        return saved
    }

    suspend fun removeExtractionReport(report: SavedExtractionProblem): Boolean {
        currentExtractionReport(report)
        val removed = extractionReports.remove(report.accountId, report.id)
        check(accountService.currentAccountIdFlow.value == report.accountId) { "Report account changed" }
        return removed
    }

    fun showWarningDialog() {
        _troubleshootingUiState.update { it.copy(warningDialogVisible = true) }
    }

    fun hideWarningDialog() {
        _troubleshootingUiState.update { it.copy(warningDialogVisible = false) }
    }

    fun tryImport(context: Context, byteArray: ByteArray) {
        //        if (!byteArray.isProbableProtobuf()) {
        //            showWarningDialog()
        //        } else {
        importPreferencesFromJSON(context, byteArray)
        //        }
    }

    fun importPreferencesFromJSON(context: Context, byteArray: ByteArray) {
        viewModelScope.launch(ioDispatcher) { String(byteArray).fromJSONStringToDataStore(context) }
    }

    fun exportPreferencesAsJSON(context: Context, callback: (ByteArray) -> Unit = {}) {
        viewModelScope.launch(ioDispatcher) {
            callback(context.fromDataStoreToJSONString().toByteArray())
        }
    }

    suspend fun getSyncLogs(): List<Log> = syncLogger.list()

    fun clearSyncLogs() = viewModelScope.launch { syncLogger.clear() }
}

data class ExtractionReportsUiState(
    val accountId: Int? = null,
    val reports: List<SavedExtractionProblem> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

data class TroubleshootingUiState(
    val isLoading: Boolean = false,
    val warningDialogVisible: Boolean = false,
)
