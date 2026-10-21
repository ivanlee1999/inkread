package me.ash.reader.domain.data

import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.preference.Settings
import me.ash.reader.infrastructure.preference.SettingsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Uses the real Paging implementation, not a replacement for cachedIn or PagingSource. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticlePagingListUseCaseLifetimeTest {
    private val dispatcher = StandardTestDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun `replaced filters stop responding to old source invalidations`() = runTest(dispatcher) {
        val fixture = fixture()
        runCurrent()
        repeat(20) { index ->
            fixture.filters.value = FilterState(feed = feed("feed-${index + 1}"))
            runCurrent()
        }
        val oldSources = fixture.sources.filter { it.feedId != "feed-20" }
        assertEquals(20, oldSources.size)
        val factoriesBeforeInvalidation = fixture.sources.size

        // This models Room invalidating sources after an article-table write. Count source
        // creation separately from load(): a recreated page-event stream may remain lazy.
        oldSources.forEach { it.invalidate() }
        runCurrent()

        assertEquals(
            "Replaced pager caches must not create new sources after their UI collector is gone",
            factoriesBeforeInvalidation,
            fixture.sources.size,
        )
    }

    @Test fun `reader and UI share one active cache across recollection and refresh`() = runTest(dispatcher) {
        val fixture = fixture()
        val ui = object : PagingDataPresenter<ArticleFlowItem>(dispatcher) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<ArticleFlowItem>) = Unit
        }
        fun collectUi() = backgroundScope.launch(dispatcher) {
            fixture.useCase.pagerFlow.collectLatest { data ->
                data.pager.collectLatest { ui.collectFrom(it) }
            }
        }
        val firstUi = collectUi()
        runCurrent()
        assertEquals(1, fixture.sources.size)
        assertEquals(1, fixture.sources.single().loads)
        assertSame(fixture.useCase.itemSnapshotList.items.first(), ui.snapshot().items.first())

        firstUi.cancel()
        runCurrent()
        val secondUi = collectUi()
        runCurrent()
        assertEquals("UI reattachment must reuse the current reader cache", 1, fixture.sources.size)
        assertEquals(1, fixture.sources.single().loads)

        ui[ui.size - 1]
        runCurrent()
        assertEquals("Reattachment must retain working access hints", 200, ui.size)
        assertEquals(2, fixture.sources.single().loads)

        ui.refresh()
        runCurrent()
        assertEquals("Explicit refresh of the same filter must still work", 2, fixture.sources.size)
        assertTrue(fixture.sources.last().loads > 0)
        fixture.sources.last().invalidate()
        runCurrent()
        assertEquals("The active filter still responds to database invalidation", 3, fixture.sources.size)
        assertTrue(fixture.sources.last().loads > 0)
        assertSame(fixture.useCase.itemSnapshotList.items.first(), ui.snapshot().items.first())
        secondUi.cancel()
    }

    private fun fixture(): Fixture {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        val filters = MutableStateFlow(FilterState(feed = feed("feed-0")))
        val filterUseCase: FilterStateUseCase = mock()
        whenever(filterUseCase.filterStateFlow).thenReturn(filters)
        val accountService: AccountService = mock()
        whenever(accountService.currentAccountIdFlow).thenReturn(MutableStateFlow<Int?>(1))
        val repository: AbstractRssRepository = mock()
        val rssService: RssService = mock()
        whenever(rssService.get()).thenReturn(repository)
        val sources = mutableListOf<CountingSource>()
        whenever(repository.pullArticles(anyOrNull(), anyOrNull(), any(), any(), any())).thenAnswer {
            CountingSource(it.getArgument<String?>(1) ?: "all").also { sources += it }
        }
        val settings: SettingsProvider = mock()
        whenever(settings.settings).thenReturn(Settings())
        return Fixture(
            ArticlePagingListUseCase(rssService, mock<AndroidStringsHelper>(), scope, dispatcher,
                settings, filterUseCase, accountService),
            filters,
            sources,
        )
    }

    private data class Fixture(
        val useCase: ArticlePagingListUseCase,
        val filters: MutableStateFlow<FilterState>,
        val sources: MutableList<CountingSource>,
    )

    private class CountingSource(val feedId: String) : PagingSource<Int, ArticleWithFeed>() {
        var loads = 0
        override fun getRefreshKey(state: PagingState<Int, ArticleWithFeed>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ArticleWithFeed> {
            loads++
            val start = params.key ?: 0
            val end = (start + params.loadSize).coerceAtMost(200)
            val items = (start until end).map { index ->
                val article = Article(
                    id = "$feedId:article:$index", date = Date(index.toLong()), title = "Article $index",
                    rawDescription = "Body", shortDescription = "Body",
                    link = "https://example.com/$feedId/$index", feedId = feedId, accountId = 1,
                )
                ArticleWithFeed(article, feed(feedId))
            }
            return LoadResult.Page(items, prevKey = null, nextKey = end.takeIf { it < 200 })
        }
    }

    companion object {
        private fun feed(id: String) = Feed(
            id = id, name = id, url = "https://example.com/$id", groupId = "group", accountId = 1,
        )
    }
}
