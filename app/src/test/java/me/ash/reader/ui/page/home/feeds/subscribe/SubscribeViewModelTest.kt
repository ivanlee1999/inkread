package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.lifecycle.ViewModelStore
import com.rometools.rome.feed.synd.SyndFeedImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.rss.RssHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SubscribeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val applicationScopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        stores.forEach { it.clear() }
        applicationScopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun `redirected feed final URL rejects an existing subscription`() = runTest(dispatcher) {
        val inputUrl = "https://example.com/old-feed.xml"
        val finalUrl = "https://example.com/current/feed.xml"
        val fixture = fixture()
        whenever(fixture.repository.isFeedExist(inputUrl)).thenReturn(false)
        whenever(fixture.repository.isFeedExist(finalUrl)).thenReturn(true)
        whenever(fixture.helper.searchFeed(inputUrl)).thenReturn(searchResult(finalUrl))

        fixture.viewModel.showDrawer()
        runCurrent()
        input(fixture.viewModel, inputUrl)
        fixture.viewModel.searchFeed()
        runCurrent()

        assertAlreadySubscribed(fixture.viewModel, inputUrl)
        fixture.viewModel.subscribe()
        runCurrent()
        verify(fixture.repository).isFeedExist(finalUrl)
        verifyNoSubscription(fixture.repository)
    }

    @Test fun `discovered feed final URL rejects an existing subscription`() = runTest(dispatcher) {
        val pageUrl = "https://example.com/blog/"
        val finalUrl = "https://feeds.example.com/canonical.atom"
        val fixture = fixture()
        whenever(fixture.repository.isFeedExist(pageUrl)).thenReturn(false)
        whenever(fixture.repository.isFeedExist(finalUrl)).thenReturn(true)
        // The helper resolves discovery and any redirect on the discovered feed.
        whenever(fixture.helper.searchFeed(pageUrl)).thenReturn(searchResult(finalUrl))

        fixture.viewModel.showDrawer()
        runCurrent()
        input(fixture.viewModel, pageUrl)
        fixture.viewModel.searchFeed()
        runCurrent()

        assertAlreadySubscribed(fixture.viewModel, pageUrl)
        fixture.viewModel.subscribe()
        runCurrent()
        verify(fixture.helper).searchFeed(pageUrl)
        verify(fixture.repository).isFeedExist(finalUrl)
        verifyNoSubscription(fixture.repository)
    }

    @Test fun `repeated searches after canonical duplicate rejection remain idle`() = runTest(dispatcher) {
        val inputUrl = "https://example.com/feed-alias"
        val finalUrl = "https://example.com/feed.xml"
        val fixture = fixture()
        whenever(fixture.repository.isFeedExist(inputUrl)).thenReturn(false)
        whenever(fixture.repository.isFeedExist(finalUrl)).thenReturn(true)
        whenever(fixture.helper.searchFeed(inputUrl)).thenReturn(searchResult(finalUrl))
        fixture.viewModel.showDrawer()
        runCurrent()
        input(fixture.viewModel, inputUrl)

        repeat(3) {
            fixture.viewModel.searchFeed()
            runCurrent()
            assertAlreadySubscribed(fixture.viewModel, inputUrl)
            fixture.viewModel.subscribe()
            runCurrent()
        }

        verify(fixture.helper, times(3)).searchFeed(inputUrl)
        verify(fixture.repository, times(3)).isFeedExist(finalUrl)
        verifyNoSubscription(fixture.repository)
    }

    @Test fun `unsubscribed canonical result still opens configuration with final URL`() = runTest(dispatcher) {
        val inputUrl = "https://example.com/blog/"
        val finalUrl = "https://example.com/feeds/main.atom"
        val result = searchResult(finalUrl)
        val fixture = fixture()
        whenever(fixture.repository.isFeedExist(any())).thenReturn(false)
        whenever(fixture.helper.searchFeed(inputUrl)).thenReturn(result)
        fixture.viewModel.showDrawer()
        runCurrent()
        input(fixture.viewModel, inputUrl)

        fixture.viewModel.searchFeed()
        runCurrent()

        val state = fixture.viewModel.subscribeState.value
        assertTrue(state is SubscribeState.Configure)
        state as SubscribeState.Configure
        assertEquals(finalUrl, state.feedLink)
        assertSame(result.feed, state.searchedFeed)
        assertEquals("1\u0024group", state.selectedGroupId)
        verify(fixture.repository).isFeedExist(finalUrl)
        verifyNoSubscription(fixture.repository)
    }

    private fun input(viewModel: SubscribeViewModel, url: String) {
        val state = viewModel.subscribeState.value as SubscribeState.Idle
        state.linkState.edit { replace(0, length, url) }
    }

    private fun assertAlreadySubscribed(viewModel: SubscribeViewModel, url: String) {
        val state = viewModel.subscribeState.value
        assertTrue("A duplicate must return to Idle rather than Configure/Fetching", state is SubscribeState.Idle)
        state as SubscribeState.Idle
        assertEquals(ALREADY_SUBSCRIBED, state.errorMessage)
        assertEquals(url, state.linkState.text.toString())
        assertTrue(state.importFromOpmlEnabled)
    }

    private suspend fun verifyNoSubscription(repository: AbstractRssRepository) {
        verify(repository, never()).subscribe(
            feedLink = any(), searchedFeed = any(), groupId = any(),
            isNotification = any(), isFullContent = any(), isBrowser = any(),
        )
    }

    private fun fixture(): Fixture {
        val repository: AbstractRssRepository = mock()
        val rss: RssService = mock()
        val helper: RssHelper = mock()
        val accounts: AccountService = mock()
        val strings: AndroidStringsHelper = mock()
        whenever(rss.get()).thenReturn(repository)
        whenever(repository.importSubscription).thenReturn(true)
        whenever(repository.pullGroups()).thenReturn(MutableStateFlow(mutableListOf(
            Group("1\u0024group", "Default", 1),
        )))
        whenever(accounts.currentAccountFlow).thenReturn(MutableStateFlow<Account?>(null))
        whenever(strings.getString(R.string.already_subscribed)).thenReturn(ALREADY_SUBSCRIBED)
        runBlocking { whenever(repository.isFeedExist(any())).thenReturn(false) }
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        applicationScopes += applicationScope
        val viewModel = SubscribeViewModel(
            opmlService = mock(), rssService = rss, rssHelper = helper,
            androidStringsHelper = strings, applicationScope = applicationScope,
            accountService = accounts,
        )
        stores += ViewModelStore().apply { put("subscribe", viewModel) }
        return Fixture(viewModel, repository, helper)
    }

    private fun searchResult(url: String) = RssHelper.SearchFeedResult(
        feed = SyndFeedImpl().apply { title = "Example Feed" }, feedLink = url,
    )

    private data class Fixture(
        val viewModel: SubscribeViewModel,
        val repository: AbstractRssRepository,
        val helper: RssHelper,
    )

    companion object {
        private const val ALREADY_SUBSCRIBED = "Already subscribed"
    }
}
