package me.ash.reader.ui.page.settings.troubleshooting

import androidx.lifecycle.ViewModelStore
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.report.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExtractionReportsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val lifecycle = ViewModelStore()
    private val account = MutableStateFlow<Int?>(1)
    private val accounts: AccountService = mock { on { currentAccountIdFlow } doReturn account }
    private val reports: ExtractionProblemReportStore = mock()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() { lifecycle.clear(); Dispatchers.resetMain() }

    private fun report(accountId: Int = 1) = SavedExtractionProblem(
        id = "report-$accountId", createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
        snapshot = ExtractionProblemSnapshot(
            capturedAtEpochMillis = 1, appVersion = "0.16.24", appVersionCode = 69,
            deviceModel = "Test device", androidApi = 30, publicArticleUrl = "https://example.com/story",
            requestedSourceMode = ExtractionSourceMode.LOCAL, displayedSourceMode = ExtractionSourceMode.ORIGINAL,
        ), notes = "Only a summary", accountId = accountId, articleId = "article-$accountId",
    )

    private fun viewModel(scope: kotlinx.coroutines.CoroutineScope): TroubleshootingViewModel =
        TroubleshootingViewModel(accounts, mock(), mock(), dispatcher, dispatcher, dispatcher,
            scope, mock(), mock(), reports).also { lifecycle.put("reports", it) }

    @Test fun accountSwitchClearsOldReportsAndDoesNotCollectOldAccountUpdates() = runTest(dispatcher) {
        val first = MutableStateFlow(listOf(report(1)))
        val second = MutableStateFlow(listOf(report(2)))
        whenever(reports.observe(1)).thenReturn(first)
        whenever(reports.observe(2)).thenReturn(second)
        val vm = viewModel(backgroundScope)
        advanceUntilIdle()
        assertEquals(listOf(report(1)), vm.extractionReportState.value.reports)
        account.value = 2
        advanceUntilIdle()
        assertEquals(2, vm.extractionReportState.value.accountId)
        assertEquals(listOf(report(2)), vm.extractionReportState.value.reports)
        first.value = emptyList()
        advanceUntilIdle()
        assertEquals(listOf(report(2)), vm.extractionReportState.value.reports)
        account.value = null
        advanceUntilIdle()
        assertNull(vm.extractionReportState.value.accountId)
        assertTrue(vm.extractionReportState.value.reports.isEmpty())
    }

    @Test fun staleDialogCannotReadWriteOrRemoveAnotherAccountsReport() = runTest(dispatcher) {
        whenever(reports.observe(any())).thenReturn(MutableStateFlow(emptyList()))
        val vm = viewModel(backgroundScope)
        account.value = 2
        val old = report(1)
        assertTrue(runCatching { vm.currentExtractionReport(old) }.isFailure)
        assertTrue(runCatching { vm.saveExtractionReportNotes(old, "changed") }.isFailure)
        assertTrue(runCatching { vm.removeExtractionReport(old) }.isFailure)
        verify(reports, never()).find(any(), any())
        verify(reports, never()).updateNotes(any(), any(), any())
        verify(reports, never()).remove(any(), any())
    }

    @Test fun accountSwitchDuringPendingReadRejectsSnapshotBeforeExport() = runTest(dispatcher) {
        whenever(reports.observe(any())).thenReturn(MutableStateFlow(emptyList()))
        val barrier = CompletableDeferred<SavedExtractionProblem?>()
        whenever(reports.find(1, "article-1")).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<SavedExtractionProblem?>
            (suspend { barrier.await() }).startCoroutineUninterceptedOrReturn(continuation)
        }
        val vm = viewModel(backgroundScope)
        val pending = async { runCatching { vm.currentExtractionReport(report()) } }
        advanceUntilIdle()
        account.value = 2
        barrier.complete(report())
        advanceUntilIdle()
        assertTrue(pending.await().isFailure)
    }

    @Test fun reopeningUsesLatestStoredNotesAndRemovedReportCannotExport() = runTest(dispatcher) {
        whenever(reports.observe(any())).thenReturn(MutableStateFlow(emptyList()))
        val latest = report().copy(notes = "Missing final paragraphs")
        whenever(reports.find(1, "article-1")).thenReturn(latest)
        val vm = viewModel(backgroundScope)
        assertEquals(latest, vm.currentExtractionReport(report()))
        whenever(reports.find(1, "article-1")).thenReturn(null)
        assertTrue(runCatching { vm.saveExtractionReportNotes(latest, "new") }.isFailure)
        verify(reports, never()).updateNotes(any(), any(), any())
    }
}
