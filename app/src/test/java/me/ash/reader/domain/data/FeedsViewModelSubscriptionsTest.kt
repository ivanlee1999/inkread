package me.ash.reader.domain.data

import androidx.lifecycle.ViewModelStore
import androidx.work.WorkManager
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.page.home.feeds.FeedsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FeedsViewModelSubscriptionsTest {
    @Test fun `summary query tracks filter and account identity but not search or sync metadata`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = ViewModelStore()
        try {
            val account = Account(id = 1, name = "Local", type = AccountType.Local)
            val accounts = MutableStateFlow<Account?>(account)
            val accountService: AccountService = mock()
            whenever(accountService.currentAccountFlow).thenReturn(accounts)
            val filters = MutableStateFlow(FilterState())
            val filterUseCase: FilterStateUseCase = mock()
            whenever(filterUseCase.filterStateFlow).thenReturn(filters)
            val groups: GroupWithFeedsListUseCase = mock()
            whenever(groups.groupWithFeedListFlow).thenReturn(MutableStateFlow(emptyList<GroupWithFeed>()))
            val diffs: DiffMapHolder = mock()
            whenever(diffs.diffMapSnapshotFlow).thenReturn(MutableStateFlow(emptyMap<String, Diff>()))
            val strings: AndroidStringsHelper = mock()
            whenever(strings.getQuantityString(any(), any(), any())).thenReturn("count")
            val workManager: WorkManager = mock()
            whenever(workManager.getWorkInfosByTagLiveData(any())).thenReturn(mock())
            var starts = 0
            var active = 0
            val counts = flow {
                starts++
                active++
                try {
                    emit(emptyMap<String, Int>())
                    awaitCancellation()
                } finally {
                    active--
                }
            }
            val repository: AbstractRssRepository = mock()
            whenever(repository.pullImportant(any(), any())).thenReturn(counts)
            val rss: RssService = mock()
            whenever(rss.get()).thenReturn(repository)
            val viewModel = FeedsViewModel(accountService, rss, workManager, strings, dispatcher,
                dispatcher, applicationScope, mock<SettingsProvider>(), diffs, filterUseCase, groups)
            store.put("feeds", viewModel)
            runCurrent()
            assertEquals(1, starts)

            filters.value = filters.value.copy(searchContent = "news")
            runCurrent()
            accounts.value = account.copy(updateAt = Date(1), name = "Renamed")
            runCurrent()
            assertEquals("Unrelated state must not restart SQL subscriptions", 1, starts)
            assertEquals("Visible metadata still updates", "Renamed", viewModel.feedsUiState.value.account!!.name)

            accounts.value = accounts.value!!.copy(id = 2)
            runCurrent()
            assertEquals(2, starts)
            accounts.value = accounts.value!!.copy(type = AccountType.Fever)
            runCurrent()
            assertEquals(3, starts)
            filters.value = filters.value.copy(filter = Filter.Unread)
            runCurrent()
            assertEquals(4, starts)
            assertEquals("Only one summary query may be active", 1, active)
        } finally {
            store.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
