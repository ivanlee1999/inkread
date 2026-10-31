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
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rss.FeedSourceHistory
import me.ash.reader.infrastructure.rss.FeedArticleRefreshResult
import me.ash.reader.infrastructure.rss.FeedArticleRefreshStatus
import me.ash.reader.ui.page.home.flow.MeasuredNewspaperRow
import me.ash.reader.ui.page.home.flow.NewspaperRow
import me.ash.reader.ui.page.home.flow.NewspaperViewportPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
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
        whenever(fixture.cache.readOrFetchFullContent(eq(a.article), any())).thenSuspend {
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
        whenever(fixture.cache.readOrFetchFullContent(eq(a.article), any())).thenSuspend {
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
        whenever(fixture.cache.readOrFetchFullContent(eq(a.article), any())).thenReturn(Result.failure(java.io.IOException("offline")))
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
        verify(fixture.cache, never()).readOrFetchFullContent(any<Article>(), any())
    }

    @Test fun `explicit retry leaves existing readable content mounted while network waits`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a)
        val fetched = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readOrFetchFullContent(eq(a.article), any())).thenSuspend { fetched.await() }
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
        whenever(fixture.cache.refreshFullContent(eq(a.article), any())).thenReturn(Result.failure(java.io.IOException("offline")))
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
        whenever(fixture.cache.readOrFetchFullContent(eq(a.article), any())).thenReturn(Result.success("description A with immediately ready complete article"))

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

    @Test fun `feed refresh updates body but preserves live read star and article identity`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a, googleReader = true)
        val refresh = CompletableDeferred<FeedArticleRefreshResult>()
        whenever(fixture.googleReader.refreshArticleFeedBody(a.article)).thenSuspend { refresh.await() }
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertTrue(fixture.viewModel.readerStateStateFlow.value.canRefreshFeed)
        fixture.viewModel.refreshFeedContent(); runCurrent()
        assertTrue(fixture.viewModel.readerStateStateFlow.value.isRefreshingFeed)
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        fixture.viewModel.updateStarredStatus(true)
        whenever(fixture.diffs.checkIfUnread(a)).thenReturn(true)
        fixture.viewModel.updateReadStatus(true)
        whenever(fixture.history.readEarlier(a.article.id, 1)).thenReturn(Result.success(a.article.rawDescription))
        refresh.complete(FeedArticleRefreshResult(a.article.copy(rawDescription = "Updated feed body", link = "https://example.com/changed"),
            FeedArticleRefreshStatus.UPDATED)); runCurrent()
        val reading = fixture.viewModel.readingUiState.value
        assertTrue(reading.isUnread)
        assertTrue(reading.isStarred)
        assertTrue(reading.articleWithFeed!!.article.isUnread)
        assertTrue(reading.articleWithFeed!!.article.isStarred)
        assertEquals(a.article.link, reading.articleWithFeed!!.article.link)
        assertEquals(a.article.link, fixture.viewModel.readerStateStateFlow.value.link)
        assertEquals(ReaderState.Description("Updated feed body"), fixture.viewModel.readerStateStateFlow.value.content)
        assertTrue(fixture.viewModel.readerStateStateFlow.value.hasPreviousFeedContent)
        assertFalse(fixture.viewModel.readerStateStateFlow.value.isRefreshingFeed)
    }

    @Test fun `reopening refreshed article ignores stale retained list body without changing edition order`() = runTest(dispatcher) {
        val a = article("A"); val b = article("B")
        val fixture = fixture(a, b, googleReader = true)
        val updated = a.copy(article = a.article.copy(rawDescription = "Durable updated feed body"))
        whenever(fixture.googleReader.refreshArticleFeedBody(a.article)).thenReturn(
            FeedArticleRefreshResult(updated.article, FeedArticleRefreshStatus.UPDATED))
        whenever(fixture.repository.findArticleById(a.article.id)).thenReturn(updated)
        fixture.viewModel.initData(a.article.id, 0); runCurrent()
        fixture.viewModel.refreshFeedContent(); runCurrent()
        fixture.viewModel.initData(b.article.id, 1); runCurrent()
        fixture.viewModel.initData(a.article.id, 0); runCurrent()
        assertEquals(ReaderState.Description(updated.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        assertEquals(b.article.id, fixture.viewModel.readerStateStateFlow.value.nextArticle?.articleId)
        assertEquals(a.article.rawDescription, fixture.paging.itemSnapshotList.items
            .filterIsInstance<ArticleFlowItem.Article>().first().articleWithFeed.article.rawDescription)
        verify(fixture.repository).findArticleById(a.article.id)
    }

    @Test fun `kept or unchanged feed refresh displays the retained feed with an accurate notice`() = runTest(dispatcher) {
        for (status in listOf(FeedArticleRefreshStatus.KEPT_CURRENT, FeedArticleRefreshStatus.UNCHANGED)) {
            val a = article(status.name)
            val fixture = fixture(a, googleReader = true)
            whenever(fixture.googleReader.refreshArticleFeedBody(a.article)).thenReturn(FeedArticleRefreshResult(a.article, status))
            fixture.viewModel.initData(a.article.id); runCurrent()
            fixture.viewModel.refreshFeedContent(); runCurrent()
            assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
            assertFalse(fixture.viewModel.readerStateStateFlow.value.isRefreshingFeed)
            assertTrue(fixture.viewModel.readerStateStateFlow.value.feedSourceMessage!!.contains(
                if (status == FeedArticleRefreshStatus.KEPT_CURRENT) "Kept" else "same"))
        }
    }

    @Test fun `concurrent feed update cannot replace a chosen imported source`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a, googleReader = true)
        whenever(fixture.cache.readBrowserText(a.article.id, 1)).thenReturn(Result.success("Imported text"))
        whenever(fixture.googleReader.refreshArticleFeedBody(a.article)).thenReturn(
            FeedArticleRefreshResult(a.article.copy(rawDescription = "Competing update"), FeedArticleRefreshStatus.CONCURRENT_CHANGE))
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.renderBrowserText(); runCurrent()
        fixture.viewModel.refreshFeedContent(); runCurrent()
        assertEquals(ReaderState.FullContent("Imported text", browserText = true), fixture.viewModel.readerStateStateFlow.value.content)
        assertTrue(fixture.viewModel.readerStateStateFlow.value.feedSourceMessage!!.contains("Another update"))
    }

    @Test fun `late feed refresh cannot replace newer article and cancelled committed id reloads from database`() = runTest(dispatcher) {
        val a = article("A"); val b = article("B")
        val fixture = fixture(a, b, googleReader = true)
        val refresh = CompletableDeferred<FeedArticleRefreshResult>()
        val updated = a.copy(article = a.article.copy(rawDescription = "Committed late body"))
        whenever(fixture.googleReader.refreshArticleFeedBody(a.article)).thenSuspend {
            withContext(NonCancellable) { refresh.await() }
        }
        whenever(fixture.repository.findArticleById(a.article.id)).thenReturn(updated)
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.refreshFeedContent(); runCurrent()
        fixture.viewModel.initData(b.article.id); runCurrent()
        refresh.complete(FeedArticleRefreshResult(updated.article, FeedArticleRefreshStatus.UPDATED)); runCurrent()
        assertEquals(b.article.id, fixture.viewModel.readerStateStateFlow.value.articleId)
        assertEquals(ReaderState.Description(b.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(ReaderState.Description(updated.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `account switch cancels pending feed refresh and clears the former account reader`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a, googleReader = true)
        val refresh = CompletableDeferred<FeedArticleRefreshResult>()
        whenever(fixture.googleReader.refreshArticleFeedBody(a.article)).thenSuspend {
            withContext(NonCancellable) { refresh.await() }
        }
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.refreshFeedContent(); runCurrent()
        fixture.accountId.value = 2; runCurrent()
        refresh.complete(FeedArticleRefreshResult(a.article.copy(rawDescription = "Wrong account result"), FeedArticleRefreshStatus.UPDATED)); runCurrent()
        assertNull(fixture.viewModel.readerStateStateFlow.value.articleId)
        assertNull(fixture.viewModel.readingUiState.value.articleWithFeed)
    }

    @Test fun `feed source history is distinct and its failure preserves mounted body`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a, googleReader = true)
        whenever(fixture.history.readEarlier(a.article.id, 1)).thenReturn(Result.success("Earlier feed"))
        whenever(fixture.history.readEarlier(a.article.id, 1, a.article.rawDescription)).thenReturn(Result.success("Earlier feed"))
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertTrue(fixture.viewModel.readerStateStateFlow.value.hasPreviousFeedContent)
        fixture.viewModel.renderPreviousFeedContent(); runCurrent()
        assertEquals(ReaderState.Description("Earlier feed", previousVersion = true), fixture.viewModel.readerStateStateFlow.value.content)
        whenever(fixture.history.readEarlier(a.article.id, 1, "Earlier feed")).thenReturn(Result.failure(FileNotFoundException()))
        fixture.viewModel.renderPreviousFeedContent(); runCurrent()
        assertEquals(ReaderState.Description("Earlier feed", previousVersion = true), fixture.viewModel.readerStateStateFlow.value.content)
        assertTrue(fixture.viewModel.readerStateStateFlow.value.feedSourceMessage!!.contains("unavailable"))
        fixture.viewModel.renderDescriptionContent()
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `feed refresh failure does not expose server errors or replace the open source`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a, googleReader = true)
        whenever(fixture.googleReader.refreshArticleFeedBody(a.article)).thenThrow(IllegalStateException("https://private.example/token"))
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.refreshFeedContent(); runCurrent()
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        assertEquals("Feed content could not be refreshed. Current content is unchanged.", fixture.viewModel.readerStateStateFlow.value.feedSourceMessage)
    }

    @Test fun `newsletter always uses feed body and never fetches a publisher even with legacy full content flag`() = runTest(dispatcher) {
        val a = article("Newsletter", fullContent = true).let { it.copy(feed = it.feed.copy(isNewsletter = true)) }
        val fixture = fixture(a)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertTrue(fixture.viewModel.readerStateStateFlow.value.isNewsletter)
        assertFalse(fixture.viewModel.readerStateStateFlow.value.canRefreshFeed)
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        fixture.viewModel.renderFullContent(); runCurrent()
        fixture.viewModel.renderFullContent(forceRefresh = true); runCurrent()
        verify(fixture.cache, never()).readFullContent(any(), any())
        verify(fixture.cache, never()).readOrFetchFullContent(any<Article>(), any())
        verify(fixture.cache, never()).refreshFullContent(any(), any())
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `current newsletter setting wins over a retained full content feed snapshot`() = runTest(dispatcher) {
        val stale = article("Newsletter", fullContent = true)
        val fixture = fixture(stale)
        whenever(fixture.repository.findFeedById(stale.feed.id)).thenReturn(stale.feed.copy(isNewsletter = true))
        fixture.viewModel.captureReaderSequence(fixture.paging.pagerFlow.value.pager,
            listOf(ArticleFlowItem.Article(stale)))
        fixture.viewModel.initData(stale.article.id, 0); runCurrent()
        assertTrue(fixture.viewModel.readerStateStateFlow.value.isNewsletter)
        assertTrue(fixture.viewModel.readingUiState.value.articleWithFeed!!.feed.isNewsletter)
        assertEquals(ReaderState.Description(stale.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        verify(fixture.cache, never()).readFullContent(any(), any())
        verify(fixture.cache, never()).readOrFetchFullContent(any<Article>(), any())
    }

    @Test fun `extract action rechecks newsletter setting after reader was opened`() = runTest(dispatcher) {
        val a = article("Newsletter")
        val fixture = fixture(a)
        fixture.viewModel.initData(a.article.id); runCurrent()
        whenever(fixture.repository.findFeedById(a.feed.id)).thenReturn(a.feed.copy(isNewsletter = true))
        fixture.viewModel.renderFullContent(forceRefresh = true); runCurrent()
        assertTrue(fixture.viewModel.readerStateStateFlow.value.isNewsletter)
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        verify(fixture.cache, never()).refreshFullContent(any(), any())
    }

    @Test fun `missing current feed policy never starts automatic extraction`() = runTest(dispatcher) {
        val a = article("Unknown", fullContent = true)
        val fixture = fixture(a)
        whenever(fixture.repository.findFeedById(a.feed.id)).thenReturn(null)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertTrue(fixture.viewModel.readerStateStateFlow.value.content is ReaderState.Error)
        assertNull(fixture.viewModel.readingUiState.value.articleWithFeed)
        verify(fixture.cache, never()).readOrFetchFullContent(any<Article>(), any())
    }

    @Test fun `queued reader extraction rechecks newsletter policy at the network boundary`() = runTest(dispatcher) {
        val a = article("Queued", fullContent = true)
        val fixture = fixture(a)
        val releaseQueue = CompletableDeferred<Unit>()
        var allowed: Boolean? = null
        whenever(fixture.cache.readOrFetchFullContent(eq(a.article), any())).thenSuspend { invocation ->
            releaseQueue.await()
            allowed = invocation.getArgument<suspend () -> Boolean>(1).invoke()
            Result.failure(IllegalStateException("Request blocked"))
        }
        fixture.viewModel.initData(a.article.id); runCurrent()
        whenever(fixture.repository.findFeedById(a.feed.id)).thenReturn(a.feed.copy(isNewsletter = true))
        releaseQueue.complete(Unit); runCurrent()
        assertEquals(false, allowed)
        assertTrue(fixture.viewModel.readerStateStateFlow.value.isNewsletter)
        assertEquals(a.article.rawDescription, fixture.viewModel.readerStateStateFlow.value.content.documentBody)
    }

    @Test fun `account switch during initial feed lookup cannot mount or extract the old article`() = runTest(dispatcher) {
        val a = article("Newsletter", fullContent = true)
        val fixture = fixture(a)
        val lookup = CompletableDeferred<Feed?>()
        whenever(fixture.repository.findFeedById(a.feed.id)).thenSuspend { withContext(NonCancellable) { lookup.await() } }
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.accountId.value = 2; runCurrent()
        lookup.complete(a.feed); runCurrent()
        assertNull(fixture.viewModel.readerStateStateFlow.value.articleId)
        assertNull(fixture.viewModel.readingUiState.value.articleWithFeed)
        verify(fixture.cache, never()).readOrFetchFullContent(any<Article>(), any())
    }

    @Test fun `explicit source choice wins over a late feed refresh`() = runTest(dispatcher) {
        val a = article("A")
        val fixture = fixture(a, googleReader = true)
        val response = CompletableDeferred<FeedArticleRefreshResult>()
        whenever(fixture.googleReader.refreshArticleFeedBody(a.article)).thenSuspend { withContext(NonCancellable) { response.await() } }
        whenever(fixture.cache.readBrowserText(a.article.id, 1)).thenReturn(Result.success("User chosen text"))
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.refreshFeedContent(); runCurrent()
        fixture.viewModel.renderBrowserText(); runCurrent()
        response.complete(FeedArticleRefreshResult(a.article.copy(rawDescription = "Late feed body"), FeedArticleRefreshStatus.UPDATED))
        runCurrent()
        assertEquals(ReaderState.FullContent("User chosen text", browserText = true), fixture.viewModel.readerStateStateFlow.value.content)
        assertFalse(fixture.viewModel.readerStateStateFlow.value.isRefreshingFeed)
    }

    @Test fun `newsletter media filtering covers all body sources without losing provenance`() {
        val body = "<p>Authoritative body</p><img src='https://mail.example.invalid/pixel' alt='Photo'>"
        val sources = listOf(
            ReaderState.Description(body, previousVersion = true),
            ReaderState.FullContent(body, browserText = true, previousVersion = true),
            ReaderState.FullContent(body, refreshFailed = true),
        )
        sources.forEach { source ->
            val filtered = newsletterReaderContent(source)
            assertTrue(filtered.documentBody!!.contains("Authoritative body"))
            assertFalse(filtered.documentBody!!.contains("<img"))
            assertFalse(filtered.documentBody!!.contains("mail.example.invalid"))
            assertEquals(source, when (filtered) {
                is ReaderState.Description -> filtered.copy(content = body)
                is ReaderState.FullContent -> filtered.copy(content = body)
                else -> error("Expected a body")
            })
            val snapshot = ReaderState(link = "https://example.com/article", isNewsletter = true, content = filtered).savedLibrarySnapshot()!!
            assertEquals(filtered.documentBody, snapshot.html)
        }
    }

    @Test fun `newsletter load failure or empty body never supplies a save URL fallback`() {
        val failed = newsletterReaderContent(ReaderState.Description("x".repeat(4 * 1024 * 1024 + 1)))
        assertTrue(failed is ReaderState.Error)
        assertNull(failed.documentBody)
        listOf(ReaderState.Loading, ReaderState.Error("Unavailable"), ReaderState.Description("  "), failed).forEach { source ->
            assertNull(ReaderState(link = "https://example.com/article", isNewsletter = true, content = source).savedLibrarySnapshot())
        }
        assertNull(ReaderState(link = "https://example.com/article", content = ReaderState.Loading).savedLibrarySnapshot()!!.html)
    }

    @Test fun `image consent still rejects active newsletter surfaces and oversized bodies`() {
        val content = ReaderState.Description("<p>Newsletter</p><img src='https://example.com/photo.png'>" +
            "<iframe src='https://example.com/confirm'></iframe><meta http-equiv='refresh' content='0;url=https://example.com/unsubscribe'>")
        val allowed = newsletterReaderContent(content, allowImages = true)
        assertTrue(allowed.documentBody!!.contains("photo.png"))
        assertFalse(allowed.documentBody!!.contains("/confirm"))
        assertFalse(allowed.documentBody!!.contains("/unsubscribe"))
        val failed = newsletterReaderContent(ReaderState.Description("x".repeat(4 * 1024 * 1024 + 1)), allowImages = true)
        assertTrue(failed is ReaderState.Error)
        assertNull(ReaderState(link = "https://example.com/article", isNewsletter = true, content = failed).savedLibrarySnapshot())
    }

    private fun fixture(
        vararg articles: ArticleWithFeed,
        applicationScheduler: TestCoroutineScheduler = dispatcher.scheduler,
        googleReader: Boolean = false,
    ): Fixture {
        val cache: ReaderCacheHelper = mock()
        val history: FeedSourceHistory = mock()
        val repository: AbstractRssRepository = if (googleReader) mock<GoogleReaderRssService>() else mock()
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
        whenever(groups.feedsFlow).thenReturn(MutableStateFlow<List<GroupWithFeed>>(emptyList()))
        whenever(filters.filterStateFlow).thenReturn(MutableStateFlow(FilterState()))
        whenever(workManager.getWorkInfosByTagFlow(SyncWorker.SYNC_TAG))
            .thenReturn(MutableStateFlow<List<WorkInfo>>(emptyList()))
        // Stubbing suspend APIs requires a coroutine context; these defaults do not suspend.
        kotlinx.coroutines.runBlocking {
            whenever(repository.findFeedById(any())).thenAnswer { invocation ->
                val id = invocation.getArgument<String>(0)
                articles.firstOrNull { it.feed.id == id }?.feed ?: article("Stub").feed.copy(id = id)
            }
            whenever(cache.readFullContent(any(), any())).thenReturn(Result.failure(FileNotFoundException()))
            whenever(cache.readBrowserText(any(), any())).thenReturn(Result.failure(FileNotFoundException()))
            whenever(cache.readPreviousContent(any(), any(), any())).thenReturn(Result.failure(FileNotFoundException()))
            whenever(history.readEarlier(any(), any(), org.mockito.kotlin.anyOrNull())).thenReturn(Result.failure(FileNotFoundException()))
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
            feedSourceHistory = history,
            textToSpeechManager = mock(),
            imageDownloader = mock(),
            articleListUseCase = paging,
            workManager = workManager,
        )
        stores += ViewModelStore().apply { put("reader", viewModel) }
        return Fixture(viewModel, cache, repository, paging, diffs, accountId, history)
    }

    private fun article(name: String, fullContent: Boolean = false): ArticleWithFeed = ArticleWithFeed(
        article = Article(
            id = "1\u0024$name", date = Date(0), title = name, rawDescription = "description $name",
            shortDescription = name, link = "https://example.com/$name", feedId = "1\u0024feed$name", accountId = 1,
        ),
        feed = Feed(
            id = "1\u0024feed$name", name = "Feed", url = "https://example.com/feed", groupId = "1\u0024group",
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
        val history: FeedSourceHistory,
    ) {
        val googleReader get() = repository as GoogleReaderRssService
    }

    // Mockito strips the final continuation from ordinary arguments. Resume the raw
    // continuation directly to allow deterministic suspension without real IO/threads.
    @Suppress("UNCHECKED_CAST")
    private fun <T> OngoingStubbing<T>.thenSuspend(block: suspend (InvocationOnMock) -> T) =
        thenAnswer { invocation ->
            val continuation = invocation.rawArguments.last() as Continuation<T>
            (suspend { block(invocation) }).startCoroutineUninterceptedOrReturn(continuation)
        }
}
