package me.ash.reader.ui.page.adaptive

import androidx.lifecycle.ViewModelStore
import androidx.paging.ItemSnapshotList
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Date
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.stubbing.OngoingStubbing

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleListReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val applicationScopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        stores.forEach { it.clear() }
        applicationScopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun `delayed article A cannot replace article B`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val b = article("B")
        val fixture = fixture(a, b)
        val diskResult = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readFullContent(a.article.id, 1)).thenSuspend {
            // Model an IO operation that finishes even after the request was cancelled.
            withContext(NonCancellable) { diskResult.await() }
        }

        fixture.viewModel.initData(a.article.id)
        runCurrent()
        fixture.viewModel.initData(b.article.id)
        runCurrent()
        diskResult.complete(Result.success("old A"))
        runCurrent()

        assertEquals(b.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(ReaderState.Description("description B"), fixture.viewModel.readerStateStateFlow.value.content)
        assertEquals(b.article.id, fixture.viewModel.readingUiState.value.articleWithFeed?.article?.id)
    }

    @Test fun `clearing the reader invalidates a delayed database lookup`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture()
        val lookup = CompletableDeferred<ArticleWithFeed?>()
        whenever(fixture.repository.findArticleById(a.article.id)).thenSuspend {
            withContext(NonCancellable) { lookup.await() }
        }

        fixture.viewModel.initData(a.article.id)
        runCurrent()
        fixture.viewModel.clearReadingData()
        lookup.complete(a)
        runCurrent()

        assertNull(fixture.viewModel.readerStateStateFlow.value.articleId)
        assertNull(fixture.viewModel.readingUiState.value.articleWithFeed)
    }

    @Test fun `description selection cancels full content and its delayed loading state`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a)
        val fetched = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readOrFetchFullContent(a.article)).thenSuspend {
            withContext(NonCancellable) { fetched.await() }
        }
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        fixture.viewModel.renderFullContent()
        runCurrent()
        fixture.viewModel.renderDescriptionContent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(ReaderState.Description("description A"), fixture.viewModel.readerStateStateFlow.value.content)

        fetched.complete(Result.success("late full content"))
        runCurrent()
        assertEquals(ReaderState.Description("description A"), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `repeated A B A navigation ignores the first A fetch`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val b = article("B")
        val fixture = fixture(a, b)
        val first = CompletableDeferred<Result<String>>()
        val latest = CompletableDeferred<Result<String>>()
        var fetchCount = 0
        whenever(fixture.cache.readOrFetchFullContent(a.article)).thenSuspend {
            val request = if (++fetchCount == 1) first else latest
            withContext(NonCancellable) { request.await() }
        }

        fixture.viewModel.initData(a.article.id)
        runCurrent()
        fixture.viewModel.initData(b.article.id)
        runCurrent()
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        latest.complete(Result.success("latest A"))
        runCurrent()
        first.complete(Result.success("stale A"))
        runCurrent()

        assertEquals(2, fetchCount)
        assertEquals(a.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(ReaderState.FullContent("latest A"), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `star action retains the article selected when tapped`() = runTest(dispatcher) {
        val a = article("A")
        val b = article("B")
        val applicationScheduler = TestCoroutineScheduler()
        val fixture = fixture(a, b, applicationScheduler = applicationScheduler)
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        fixture.viewModel.updateStarredStatus(true)
        fixture.viewModel.initData(b.article.id)
        runCurrent()
        applicationScheduler.runCurrent()

        verify(fixture.repository).markAsStarred(a.article.id, true)
        verify(fixture.repository, never()).markAsStarred(b.article.id, true)
        assertEquals(b.article.id, fixture.viewModel.readingUiState.value.articleWithFeed?.article?.id)
        assertEquals(false, fixture.viewModel.readingUiState.value.isStarred)
    }

    @Test fun `immediate full content completion is not overwritten by loading`() = runTest(dispatcher) {
        // Main.immediate can run nested reader fetches before initData returns.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val a = article("A", fullContent = true)
        val fixture = fixture(a)
        whenever(fixture.cache.readOrFetchFullContent(a.article)).thenReturn(Result.success("ready A"))

        fixture.viewModel.initData(a.article.id)
        runCurrent()

        assertEquals(ReaderState.FullContent("ready A"), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `initial full content failure keeps persisted feed HTML readable`() = runTest(dispatcher) {
        val summary = "<p>Persisted article text</p>"
        val a = article("A", fullContent = true, description = summary)
        val fixture = fixture(a)
        whenever(fixture.cache.readOrFetchFullContent(a.article))
            .thenReturn(Result.failure(IOException("Offline")))

        fixture.viewModel.initData(a.article.id)
        runCurrent()

        assertEquals(a.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(ReaderState.Description(summary), fixture.viewModel.readerStateStateFlow.value.content)
        verify(fixture.cache).readFullContent(a.article.id, a.article.accountId)
        verify(fixture.cache).readOrFetchFullContent(a.article)
    }

    @Test fun `explicit full content failure falls back to persisted feed HTML`() = runTest(dispatcher) {
        val a = article("A", description = "<p>Available offline</p>")
        val fixture = fixture(a)
        val fetched = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readOrFetchFullContent(a.article)).thenSuspend { fetched.await() }
        fixture.viewModel.initData(a.article.id)
        runCurrent()

        fixture.viewModel.renderFullContent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(ReaderState.Loading, fixture.viewModel.readerStateStateFlow.value.content)
        fetched.complete(Result.failure(IOException("Connection unavailable")))
        runCurrent()

        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `full content failure with blank feed text retains error`() = runTest(dispatcher) {
        val a = article("A", fullContent = true, description = " \n\t ")
        val fixture = fixture(a)
        whenever(fixture.cache.readOrFetchFullContent(a.article))
            .thenReturn(Result.failure(IOException("Offline")))

        fixture.viewModel.initData(a.article.id)
        runCurrent()

        assertEquals(ReaderState.Error("Offline"), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `cached full content remains preferred over offline summary without fetching`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val fixture = fixture(a)
        whenever(fixture.cache.readFullContent(a.article.id, a.article.accountId))
            .thenReturn(Result.success("<p>Cached full article</p>"))

        fixture.viewModel.initData(a.article.id)
        runCurrent()

        assertEquals(ReaderState.FullContent("<p>Cached full article</p>"), fixture.viewModel.readerStateStateFlow.value.content)
        verify(fixture.cache, never()).readOrFetchFullContent(any())
    }

    @Test fun `cancelled disk result propagates from initial rendering`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val fixture = fixture(a)
        val cancellation = CancellationException("Reader closed")
        whenever(fixture.cache.readFullContent(a.article.id, a.article.accountId))
            .thenReturn(Result.failure(cancellation))

        try {
            with(fixture.viewModel) { ReaderState().renderContent(a) }
            fail("Cancelled disk reads must propagate rather than fetch or fall back")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        verify(fixture.cache, never()).readOrFetchFullContent(any())
    }

    @Test fun `cancelled fetch result does not become offline fallback or an error`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val fixture = fixture(a)
        whenever(fixture.cache.readOrFetchFullContent(a.article))
            .thenReturn(Result.failure(CancellationException("Navigation interrupted")))

        fixture.viewModel.initData(a.article.id)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        assertEquals(ReaderState.Loading, fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `failed old fetch cannot apply summary fallback after navigation`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val b = article("B")
        val fixture = fixture(a, b)
        val fetched = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readOrFetchFullContent(a.article)).thenSuspend {
            withContext(NonCancellable) { fetched.await() }
        }
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        fixture.viewModel.initData(b.article.id)
        runCurrent()
        fetched.complete(Result.failure(IOException("Old request failed")))
        runCurrent()

        assertEquals(b.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(ReaderState.Description(b.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `reader account identity comes from the loaded article and clears with it`() = runTest(dispatcher) {
        val a = article("A", accountId = 7)
        val b = article("B", accountId = 9)
        val fixture = fixture(a, b)
        assertNull(fixture.viewModel.readerStateStateFlow.value.accountId)

        fixture.viewModel.initData(a.article.id)
        runCurrent()
        assertEquals(a.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(7, fixture.viewModel.readerStateStateFlow.value.accountId)

        fixture.viewModel.initData(b.article.id)
        runCurrent()
        assertEquals(b.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(9, fixture.viewModel.readerStateStateFlow.value.accountId)

        fixture.viewModel.clearReadingData()
        assertNull(fixture.viewModel.readerStateStateFlow.value.articleId)
        assertNull(fixture.viewModel.readerStateStateFlow.value.accountId)
    }

    private fun fixture(
        vararg articles: ArticleWithFeed,
        applicationScheduler: TestCoroutineScheduler = dispatcher.scheduler,
    ): Fixture {
        val cache: ReaderCacheHelper = mock()
        val repository: AbstractRssRepository = mock()
        val rssService: RssService = mock()
        val paging: ArticlePagingListUseCase = mock()
        val groups: GroupWithFeedsListUseCase = mock()
        val filters: FilterStateUseCase = mock()
        val workManager: WorkManager = mock()
        whenever(rssService.get()).thenReturn(repository)
        whenever(paging.pagerFlow).thenReturn(MutableStateFlow(PagerData()))
        whenever(paging.itemSnapshotList).thenReturn(ItemSnapshotList(
            placeholdersBefore = 0,
            placeholdersAfter = 0,
            items = articles.map { ArticleFlowItem.Article(it) },
        ))
        whenever(groups.groupWithFeedListFlow).thenReturn(MutableStateFlow<List<GroupWithFeed>>(emptyList()))
        whenever(filters.filterStateFlow).thenReturn(MutableStateFlow(FilterState()))
        whenever(workManager.getWorkInfosByTagFlow(SyncWorker.SYNC_TAG))
            .thenReturn(MutableStateFlow<List<WorkInfo>>(emptyList()))
        // Stubbing suspend APIs requires a coroutine context; these defaults do not suspend.
        kotlinx.coroutines.runBlocking {
            whenever(cache.readFullContent(any(), any())).thenReturn(Result.failure(FileNotFoundException()))
        }
        val applicationDispatcher = StandardTestDispatcher(applicationScheduler)
        val applicationScope = CoroutineScope(SupervisorJob() + applicationDispatcher)
        applicationScopes += applicationScope
        val viewModel = ArticleListReaderViewModel(
            rssService = rssService,
            ioDispatcher = applicationDispatcher,
            applicationScope = applicationScope,
            diffMapHolder = mock(),
            filterStateUseCase = filters,
            groupWithFeedsListUseCase = groups,
            settingsProvider = mock(),
            readerCacheHelper = cache,
            textToSpeechManager = mock(),
            imageDownloader = mock(),
            articleListUseCase = paging,
            workManager = workManager,
        )
        stores += ViewModelStore().apply { put("reader", viewModel) }
        return Fixture(viewModel, cache, repository)
    }

    private fun article(
        name: String,
        fullContent: Boolean = false,
        description: String = "description $name",
        accountId: Int = 1,
    ): ArticleWithFeed = ArticleWithFeed(
        article = Article(
            id = "$accountId\u0024$name", date = Date(0), title = name, rawDescription = description,
            shortDescription = name, link = "https://example.com/$name", feedId = "$accountId\u0024feed", accountId = accountId,
        ),
        feed = Feed(
            id = "$accountId\u0024feed", name = "Feed", url = "https://example.com/feed", groupId = "$accountId\u0024group",
            accountId = accountId, isFullContent = fullContent,
        ),
    )

    private data class Fixture(
        val viewModel: ArticleListReaderViewModel,
        val cache: ReaderCacheHelper,
        val repository: AbstractRssRepository,
    )

    // Mockito strips the final continuation from ordinary arguments. Resume the raw
    // continuation directly to allow deterministic suspension without real IO/threads.
    @Suppress("UNCHECKED_CAST")
    private fun <T> OngoingStubbing<T>.thenSuspend(block: suspend (InvocationOnMock) -> T) =
        thenAnswer { invocation ->
            val continuation = invocation.rawArguments.last() as Continuation<T>
            (suspend { block(invocation) }).startCoroutineUninterceptedOrReturn(continuation)
        }
}
