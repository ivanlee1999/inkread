package me.ash.reader.domain.data

import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.preference.Settings
import me.ash.reader.infrastructure.preference.HideEmptyGroupsPreference
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.infrastructure.preference.SettingsProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Counters observe actual production collectors; no timing/latency claims are made. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupWithFeedsListUseCaseSubscriptionsTest {
    @Test fun `feed selection and search do not restart the same count query`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val fixture = fixture(scope)
            runCurrent()
            assertEquals(1, fixture.counts.starts)
            fixture.filters.value = fixture.filters.value.copy(
                feed = Feed("feed", "Feed", url = "https://example.com", groupId = "group", accountId = 1),
            )
            runCurrent()
            fixture.filters.value = fixture.filters.value.copy(searchContent = "news")
            runCurrent()
            assertEquals("Only the All/Unread/Starred filter determines the count query", 1, fixture.counts.starts)
            assertEquals(1, fixture.counts.active)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `sync timestamp update does not restart account feeds or counts`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val fixture = fixture(scope)
            runCurrent()
            fixture.accounts.value = fixture.accounts.value!!.copy(updateAt = Date(1))
            runCurrent()
            assertEquals("The account identity did not change", 1, fixture.feeds.starts)
            assertEquals("Sync completion must not resubscribe an unchanged count query", 1, fixture.counts.starts)
            assertEquals(1, fixture.feeds.active)
            assertEquals(1, fixture.counts.active)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `account identity and service type changes restart both queries`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val fixture = fixture(scope)
            runCurrent()
            fixture.accounts.value = fixture.accounts.value!!.copy(id = 2)
            runCurrent()
            assertEquals(2, fixture.feeds.starts)
            assertEquals(2, fixture.counts.starts)
            fixture.accounts.value = fixture.accounts.value!!.copy(type = AccountType.Fever)
            runCurrent()
            assertEquals(3, fixture.feeds.starts)
            assertEquals(3, fixture.counts.starts)
            assertEquals(1, fixture.feeds.active)
            assertEquals(1, fixture.counts.active)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `unread visibility responds to hide empty setting without restarting queries`() = runTest {
        verifyHideEmptyPreference(Filter.Unread)
    }

    @Test fun `starred visibility responds to hide empty setting without restarting queries`() = runTest {
        verifyHideEmptyPreference(Filter.Starred)
    }

    @Test fun `all articles retains empty custom groups and feeds regardless of hide empty setting`() = runTest {
        verifyHideEmptyPreference(Filter.All)
    }

    private suspend fun TestScope.verifyHideEmptyPreference(filter: Filter) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        fun feed(id: String, group: String) = Feed(id, id, url = "https://example.com/$id", groupId = group, accountId = 1)
        val groups = listOf(
            GroupWithFeed(Group(1.getDefaultGroupId(), "Default", 1), mutableListOf()),
            GroupWithFeed(Group("empty-custom", "Empty custom", 1), mutableListOf()),
            GroupWithFeed(Group("mixed", "Mixed", 1), mutableListOf(feed("active", "mixed"), feed("empty", "mixed"))),
            GroupWithFeed(Group("zero", "Zero", 1), mutableListOf(feed("zero-feed", "zero"))),
        )
        val visibleWhenOff = listOf("empty-custom", "mixed", "zero")
        try {
            val fixture = fixture(scope, filter, groups, mapOf("active" to 2))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(visibleWhenOff, fixture.useCase.groupWithFeedListFlow.value.map { it.group.id })
            assertEquals(2, fixture.useCase.groupWithFeedListFlow.value.first { it.group.id == "mixed" }.feeds.size)

            fixture.settings.value = fixture.settings.value.copy(hideEmptyGroups = HideEmptyGroupsPreference.ON)
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            val hidden = fixture.useCase.groupWithFeedListFlow.value
            if (filter == Filter.All) {
                assertEquals(visibleWhenOff, hidden.map { it.group.id })
                assertEquals(2, hidden.first { it.group.id == "mixed" }.feeds.size)
            } else {
                assertEquals(listOf("mixed"), hidden.map { it.group.id })
                assertEquals(listOf("active"), hidden.single().feeds.map { it.id })
                assertEquals(2, hidden.single().feeds.single().important)
            }

            fixture.settings.value = fixture.settings.value.copy(hideEmptyGroups = HideEmptyGroupsPreference.OFF)
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(visibleWhenOff, fixture.useCase.groupWithFeedListFlow.value.map { it.group.id })
            assertEquals(2, fixture.useCase.groupWithFeedListFlow.value.first { it.group.id == "mixed" }.feeds.size)
            assertEquals("Preference updates must not restart feed SQL", 1, fixture.feeds.starts)
            assertEquals("Preference updates must not restart count SQL", 1, fixture.counts.starts)
        } finally {
            scope.cancel()
        }
    }

    private fun fixture(
        scope: CoroutineScope,
        filter: Filter = Filter.All,
        initialGroups: List<GroupWithFeed> = emptyList(),
        initialCounts: Map<String, Int> = emptyMap(),
    ): Fixture {
        val accounts = MutableStateFlow<Account?>(Account(id = 1, name = "Local", type = AccountType.Local))
        val accountService: AccountService = mock()
        whenever(accountService.currentAccountFlow).thenReturn(accounts)
        whenever(accountService.getCurrentAccountId()).thenReturn(1)
        val filters = MutableStateFlow(FilterState(filter = filter))
        val filterUseCase: FilterStateUseCase = mock()
        whenever(filterUseCase.filterStateFlow).thenReturn(filters)
        val settings: SettingsProvider = mock()
        val settingsFlow = MutableStateFlow(Settings(hideEmptyGroups = HideEmptyGroupsPreference.OFF))
        whenever(settings.settingsFlow).thenReturn(settingsFlow)
        val diffs: DiffMapHolder = mock()
        whenever(diffs.diffMapSnapshotFlow).thenReturn(MutableStateFlow(emptyMap<String, Diff>()))
        val feeds = Subscriptions<MutableList<GroupWithFeed>>(initialGroups.toMutableList())
        val counts = Subscriptions<Map<String, Int>>(initialCounts)
        val repository: AbstractRssRepository = mock()
        whenever(repository.pullFeeds()).thenReturn(feeds.flow)
        whenever(repository.pullImportant(any(), any())).thenReturn(counts.flow)
        val rss: RssService = mock()
        whenever(rss.get()).thenReturn(repository)
        whenever(rss.get(any())).thenReturn(repository)
        val useCase = GroupWithFeedsListUseCase(scope, scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher,
            settings, rss, filterUseCase, diffs, accountService)
        return Fixture(accounts, filters, feeds, counts, settingsFlow, useCase)
    }

    private data class Fixture(
        val accounts: MutableStateFlow<Account?>,
        val filters: MutableStateFlow<FilterState>,
        val feeds: Subscriptions<MutableList<GroupWithFeed>>,
        val counts: Subscriptions<Map<String, Int>>,
        val settings: MutableStateFlow<Settings>,
        val useCase: GroupWithFeedsListUseCase,
    )

    private class Subscriptions<T>(initial: T) {
        var starts = 0
        var active = 0
        val flow: Flow<T> = flow {
            starts++
            active++
            try {
                emit(initial)
                awaitCancellation()
            } finally {
                active--
            }
        }
    }
}
