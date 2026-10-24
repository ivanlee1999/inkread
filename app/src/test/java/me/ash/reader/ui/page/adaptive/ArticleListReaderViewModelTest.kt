package me.ash.reader.ui.page.adaptive

import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.mutableStateMapOf
import androidx.paging.ItemSnapshotList
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.FileNotFoundException
import java.util.Date
import kotlin.coroutines.Continuation
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
import me.ash.reader.domain.data.Diff
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.ui.page.home.flow.MeasuredNewspaperRow
import me.ash.reader.ui.page.home.flow.NewspaperRow
import me.ash.reader.ui.page.home.flow.NewspaperViewportPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
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

    @Test fun `explicit import wins over a delayed initial cache read`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val fixture = fixture(a)
        val initial = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readFullContent(a.article.id, 1)).thenSuspend {
            withContext(NonCancellable) { initial.await() }
        }
        fixture.viewModel.initData(a.article.id); runCurrent()
        whenever(fixture.cache.importBrowserText(a.article.id, 1, "User chosen body"))
            .thenReturn(Result.success("<p>User chosen body</p>"))
        fixture.viewModel.importBrowserText("User chosen body"); runCurrent()
        initial.complete(Result.success("<p>Older cached body</p>")); runCurrent()
        assertEquals(ReaderState.FullContent("<p>User chosen body</p>", browserText = true), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `browser text is an explicit alternate source and feed remains available`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a)
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        whenever(fixture.cache.importBrowserText(a.article.id, 1, "Visible body"))
            .thenReturn(Result.success("<p>Visible body</p>"))
        fixture.viewModel.importBrowserText("Visible body")
        runCurrent()
        assertEquals(ReaderState.FullContent("<p>Visible body</p>", browserText = true), fixture.viewModel.readerStateStateFlow.value.content)
        org.junit.Assert.assertTrue(fixture.viewModel.readerStateStateFlow.value.hasBrowserText)
        fixture.viewModel.renderDescriptionContent()
        assertEquals(ReaderState.Description("description A"), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `late imported text cannot replace a newer article`() = runTest(dispatcher) {
        val a = article("A"); val b = article("B")
        val fixture = fixture(a, b)
        fixture.viewModel.initData(a.article.id); runCurrent()
        val imported = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.importBrowserText(a.article.id, 1, "Visible body")).thenSuspend {
            withContext(NonCancellable) { imported.await() }
        }
        fixture.viewModel.importBrowserText("Visible body"); runCurrent()
        fixture.viewModel.initData(b.article.id); runCurrent()
        imported.complete(Result.success("<p>Old imported A</p>")); runCurrent()
        assertEquals(b.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(ReaderState.Description("description B"), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `access limited extraction retains feed and exposes browser status`() {
        val failure = me.ash.reader.infrastructure.html.ArticleRequiresBrowserException(
            me.ash.reader.infrastructure.html.ArticleExtractionPreparation.AccessReason.PUBLISHER_RESTRICTED)
        assertEquals(ReaderState.Description("<p>Feed original</p>", fallback = true, needsBrowser = true),
            selectReaderContent("<p>Feed original</p>", null, failure))
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

    @Test fun `reader close clears loading ownership but retains exact unread edition page for reopening`() = runTest(dispatcher) {
        val a = article("A")
        val b = article("B")
        val c = article("C")
        val fixture = fixture(a, b, c)
        val pager = fixture.paging.pagerFlow.value.pager
        val source = listOf(a, b, c).map { ArticleFlowItem.Article(it) }
        val edition = fixture.viewModel.newspaperViewportState
        edition.acceptSnapshot(pager, source)
        val pages = source.indices.map {
            NewspaperViewportPage(listOf(MeasuredNewspaperRow(NewspaperRow(listOf(it), false, false), 100)))
        }
        edition.updatePages(pages, "full-window")
        edition.presented(edition.displayedIndices)
        edition.turn(true, false) { error("Opening/closing must not mark the list") }
        edition.presented(edition.displayedIndices)
        fixture.viewModel.captureReaderSequence(pager, source)
        fixture.viewModel.initData(b.article.id, 1)
        runCurrent()
        edition.recordReadStatus(b.article.id, false)
        edition.acceptSnapshot(pager, listOf(source[0], source[2]))

        fixture.viewModel.clearReadingData()

        assertNull(fixture.viewModel.readerStateStateFlow.value.articleId)
        assertNull(fixture.viewModel.readingUiState.value.articleWithFeed)
        assertEquals(1, edition.currentPage)
        assertEquals(pages, edition.pages)
        assertEquals(listOf(b.article.id), edition.displayedArticles.map { it.article.id })
        assertEquals(false, edition.displayedArticles.single().article.isUnread)
        fixture.viewModel.captureReaderSequence(pager, edition.items)
        fixture.viewModel.initData(b.article.id, 1)
        runCurrent()
        assertEquals(b.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(a.article.id, fixture.viewModel.readerStateStateFlow.value.previousArticle?.articleId)
        assertEquals(c.article.id, fixture.viewModel.readerStateStateFlow.value.nextArticle?.articleId)
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
        latest.complete(Result.success("description A with latest complete article"))
        runCurrent()
        first.complete(Result.success("stale A"))
        runCurrent()

        assertEquals(2, fetchCount)
        assertEquals(a.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(ReaderState.FullContent("description A with latest complete article"), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `failed extraction keeps usable feed and original article identity`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val fixture = fixture(a)
        whenever(fixture.cache.readOrFetchFullContent(a.article)).thenReturn(Result.failure(java.io.IOException("offline")))
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        assertEquals(ReaderState.Description("description A", fallback = true), fixture.viewModel.readerStateStateFlow.value.content)
        assertEquals(a.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(1, fixture.viewModel.readerStateStateFlow.value.sourceAccountId)
    }

    @Test fun `worse cached extraction keeps feed without fetching again`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val fixture = fixture(a)
        whenever(fixture.cache.readFullContent(a.article.id, 1)).thenReturn(Result.success("X"))
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        assertEquals(ReaderState.Description("description A", fallback = true), fixture.viewModel.readerStateStateFlow.value.content)
        verify(fixture.cache, never()).readOrFetchFullContent(any<Article>())
    }

    @Test fun `explicit retry leaves existing readable content mounted while network waits`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a)
        val fetched = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readOrFetchFullContent(a.article)).thenSuspend { fetched.await() }
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        fixture.viewModel.renderFullContent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(ReaderState.Description("description A"), fixture.viewModel.readerStateStateFlow.value.content)
        fetched.complete(Result.failure(java.io.IOException("offline")))
        runCurrent()
        assertEquals(ReaderState.Description("description A", fallback = true), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `failed refresh keeps already open offline extraction`() = runTest(dispatcher) {
        val a = article("A", fullContent = true)
        val fixture = fixture(a)
        val body = "description A with the complete offline article"
        whenever(fixture.cache.readFullContent(a.article.id, 1)).thenReturn(Result.success(body))
        whenever(fixture.cache.refreshFullContent(a.article)).thenReturn(Result.failure(java.io.IOException("offline")))
        fixture.viewModel.initData(a.article.id)
        runCurrent()
        fixture.viewModel.renderFullContent(forceRefresh = true)
        runCurrent()
        assertEquals(ReaderState.FullContent(body, refreshFailed = true), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `fallback and refresh notices do not change annotation document identity`() {
        val body = "<p>The same mounted article.</p>"
        assertEquals(ReaderState.FullContent(body).documentBody,
            ReaderState.FullContent(body, refreshFailed = true).documentBody)
        assertEquals(ReaderState.Description(body).documentBody,
            ReaderState.Description(body, fallback = true).documentBody)
        assertEquals(ReaderState.Description(body).documentBody, ReaderState.FullContent(body).documentBody)
        assertNull(ReaderState.Loading.documentBody)
        assertNull(ReaderState.Error("unavailable").documentBody)
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
        whenever(fixture.cache.readOrFetchFullContent(a.article)).thenReturn(Result.success("description A with immediately ready complete article"))

        fixture.viewModel.initData(a.article.id)
        runCurrent()

        assertEquals(ReaderState.FullContent("description A with immediately ready complete article"), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `reader retains neighbours when current unread article disappears`() = runTest(dispatcher) {
        val a = article("A")
        val b = article("B")
        val c = article("C")
        val fixture = fixture(a, b, c)
        fixture.viewModel.initData(b.article.id, 1)
        runCurrent()
        whenever(fixture.paging.itemSnapshotList).thenReturn(ItemSnapshotList(
            placeholdersBefore = 0, placeholdersAfter = 0,
            items = listOf(a, c).map { ArticleFlowItem.Article(it) },
        ))

        // Its old index now names C, but opening B must still resolve B by identity.
        fixture.viewModel.initData(b.article.id, 1)
        runCurrent()
        val state = fixture.viewModel.readerStateStateFlow.value
        assertEquals(b.article.id, state.articleId)
        assertEquals(a.article.id, state.previousArticle?.articleId)
        assertEquals(c.article.id, state.nextArticle?.articleId)
        assertEquals(2, state.articleOrdinal)
        assertEquals(3, state.totalArticleCount)
        verify(fixture.repository, never()).findArticleById(b.article.id)

        fixture.viewModel.initData(c.article.id, state.nextArticle?.index)
        runCurrent()
        assertEquals(b.article.id, fixture.viewModel.readerStateStateFlow.value.previousArticle?.articleId)
    }

    @Test fun `loading claims requested reader identity before suspended lookup`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture()
        val lookup = CompletableDeferred<ArticleWithFeed?>()
        whenever(fixture.repository.findArticleById(a.article.id)).thenSuspend { lookup.await() }
        fixture.viewModel.initData(a.article.id)
        assertEquals(a.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(ReaderState.Loading, fixture.viewModel.readerStateStateFlow.value.content)
        assertNull(fixture.viewModel.readingUiState.value.articleWithFeed)
        fixture.viewModel.updateReadStatus(false) // A loading toolbar must be a safe no-op.
        runCurrent()
        lookup.complete(a)
        runCurrent()
    }

    @Test fun `retained edition seeds neighbours when reopening a story removed from unread`() = runTest(dispatcher) {
        val a = article("A")
        val b = article("B")
        val c = article("C")
        val fixture = fixture(c)
        fixture.viewModel.captureReaderSequence(fixture.paging.pagerFlow.value.pager,
            listOf(a, b, c).map { ArticleFlowItem.Article(it) })
        fixture.viewModel.initData(b.article.id, 1)
        runCurrent()
        val state = fixture.viewModel.readerStateStateFlow.value
        assertEquals(b.article.id, state.articleId)
        assertEquals(a.article.id, state.previousArticle?.articleId)
        assertEquals(c.article.id, state.nextArticle?.articleId)
        assertEquals(2, state.articleOrdinal)
        verify(fixture.repository, never()).findArticleById(b.article.id)
    }

    @Test fun `bulk database read action mirrors only its exact retained edition scope`() = runTest(dispatcher) {
        val a = article("A")
        val b = article("B").let { it.copy(feed = it.feed.copy(groupId = "other-group")) }
        val fixture = fixture(a, b)
        val edition = fixture.viewModel.newspaperViewportState
        edition.acceptSnapshot(fixture.paging.pagerFlow.value.pager,
            listOf(a, b).map { ArticleFlowItem.Article(it) })
        fixture.viewModel.updateReadStatus(a.feed.groupId, null, null, MarkAsReadConditions.All, false)
        assertEquals(false, edition.currentArticle(a).article.isUnread)
        assertEquals(true, edition.currentArticle(b).article.isUnread)
        runCurrent()
        verify(fixture.repository).markAsRead(a.feed.groupId, null, null, null, false)
    }

    @Test fun `new bulk read reconciles an older pending unread only inside its edition scope`() = runTest(dispatcher) {
        val a = article("A")
        val b = article("B").let { it.copy(feed = it.feed.copy(groupId = "other-group")) }
        val fixture = fixture(a, b)
        val edition = fixture.viewModel.newspaperViewportState
        edition.acceptSnapshot(fixture.paging.pagerFlow.value.pager,
            listOf(a, b).map { ArticleFlowItem.Article(it) })
        fixture.diffs.diffMap[a.article.id] = Diff(isUnread = true, articleWithFeed = a)
        fixture.diffs.diffMap[b.article.id] = Diff(isUnread = true, articleWithFeed = b)
        // DiffMapHolder removes an existing opposite diff when updateDiff supplies a value.
        doAnswer {
            fixture.diffs.diffMap.remove(a.article.id)
            null
        }.whenever(fixture.diffs).updateDiff(a, isUnread = false)

        fixture.viewModel.updateReadStatus(a.feed.groupId, null, null, MarkAsReadConditions.All, false)
        assertEquals(false, fixture.diffs.diffMap[a.article.id]?.isUnread ?: edition.currentArticle(a).article.isUnread)
        assertEquals(false, edition.currentArticle(a).article.isUnread)
        assertEquals(true, fixture.diffs.diffMap[b.article.id]?.isUnread)
        verify(fixture.diffs).updateDiff(a, isUnread = false)
        verify(fixture.diffs, never()).updateDiff(b, isUnread = false)
        runCurrent()
        verify(fixture.repository).markAsRead(a.feed.groupId, null, null, null, false)
    }

    @Test fun notebookReaderRejectsWrongActiveAccountBeforeAnyReadMutation() = runTest(dispatcher) {
        val item = article("guarded")
        val fixture = fixture(item)
        fixture.accountId.value = 2
        fixture.viewModel.initData(item.article.id, expectedAccountId = 1)
        runCurrent()
        verify(fixture.diffs, never()).updateDiff(item, isUnread = false)
        assertNull(fixture.viewModel.readingUiState.value.articleWithFeed)
        org.junit.Assert.assertTrue(fixture.viewModel.readerStateStateFlow.value.content is ReaderState.Error)
    }

    @Test fun notebookReaderRejectsWrongArticleAccountBeforeAnyReadMutation() = runTest(dispatcher) {
        val base = article("wrong-account")
        val item = base.copy(article = base.article.copy(accountId = 2))
        val fixture = fixture(item)
        fixture.viewModel.initData(item.article.id, expectedAccountId = 1)
        runCurrent()
        verify(fixture.diffs, never()).updateDiff(item, isUnread = false)
        assertNull(fixture.viewModel.readingUiState.value.articleWithFeed)
        org.junit.Assert.assertTrue(fixture.viewModel.readerStateStateFlow.value.content is ReaderState.Error)
    }

    private fun fixture(
        vararg articles: ArticleWithFeed,
        applicationScheduler: TestCoroutineScheduler = dispatcher.scheduler,
    ): Fixture {
        val cache: ReaderCacheHelper = mock()
        val repository: AbstractRssRepository = mock()
        val rssService: RssService = mock()
        val accountService: AccountService = mock()
        val accountId = MutableStateFlow<Int?>(1)
        whenever(accountService.currentAccountIdFlow).thenReturn(accountId)
        val paging: ArticlePagingListUseCase = mock()
        val groups: GroupWithFeedsListUseCase = mock()
        val filters: FilterStateUseCase = mock()
        val workManager: WorkManager = mock()
        val diffs: DiffMapHolder = mock()
        whenever(diffs.diffMap).thenReturn(mutableStateMapOf())
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
            whenever(cache.readBrowserText(any(), any())).thenReturn(Result.failure(FileNotFoundException()))
            whenever(cache.readPreviousContent(any(), any(), any())).thenReturn(Result.failure(FileNotFoundException()))
        }
        val applicationDispatcher = StandardTestDispatcher(applicationScheduler)
        val applicationScope = CoroutineScope(SupervisorJob() + applicationDispatcher)
        applicationScopes += applicationScope
        val viewModel = ArticleListReaderViewModel(
            rssService = rssService,
            accountService = accountService,
            ioDispatcher = applicationDispatcher,
            applicationScope = applicationScope,
            diffMapHolder = diffs,
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
        return Fixture(viewModel, cache, repository, paging, diffs, accountId)
    }

    private fun article(name: String, fullContent: Boolean = false): ArticleWithFeed = ArticleWithFeed(
        article = Article(
            id = "1\u0024$name", date = Date(0), title = name, rawDescription = "description $name",
            shortDescription = name, link = "https://example.com/$name", feedId = "1\u0024feed", accountId = 1,
        ),
        feed = Feed(
            id = "1\u0024feed", name = "Feed", url = "https://example.com/feed", groupId = "1\u0024group",
            accountId = 1, isFullContent = fullContent,
        ),
    )

    private data class Fixture(
        val viewModel: ArticleListReaderViewModel,
        val cache: ReaderCacheHelper,
        val repository: AbstractRssRepository,
        val paging: ArticlePagingListUseCase,
        val diffs: DiffMapHolder,
        val accountId: MutableStateFlow<Int?>,
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
