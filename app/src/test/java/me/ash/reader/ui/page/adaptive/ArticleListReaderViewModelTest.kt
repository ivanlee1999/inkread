package me.ash.reader.ui.page.adaptive

import android.content.Context
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
import me.ash.reader.domain.model.feed.SubscriptionDisplayMode
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.readerCacheFileName
import me.ash.reader.infrastructure.html.ArticleExtractionPreparation
import me.ash.reader.infrastructure.html.ModernReadability
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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.jsoup.Jsoup
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.stubbing.OngoingStubbing

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleListReaderViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
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
        whenever(fixture.diffs.checkIfUnread(any())).thenReturn(true)
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

    @Test fun `explicit local mode survives extraction failure and affects later articles only in its subscription`() = runTest(dispatcher) {
        val first = article("ModeA")
        val next = article("ModeNext").let { it.copy(article = it.article.copy(feedId = first.feed.id), feed = first.feed) }
        val other = article("OtherSubscription")
        val fixture = fixture(first, next, other)
        whenever(fixture.cache.readOrFetchFullContent(any<Article>(), any())).thenReturn(Result.failure(FileNotFoundException()))
        fixture.viewModel.initData(first.article.id); runCurrent()
        fixture.viewModel.renderFullContent(); runCurrent()
        assertEquals(SubscriptionDisplayMode.LOCAL, fixture.feeds[first.feed.id]!!.selectedDisplayMode)
        assertTrue(fixture.viewModel.readerStateStateFlow.value.content is ReaderState.Description)
        assertNull(fixture.feeds[other.feed.id]!!.displayMode)
        fixture.viewModel.initData(next.article.id); runCurrent()
        verify(fixture.cache).readOrFetchFullContent(eq(next.article), any())
        assertEquals(SubscriptionDisplayMode.LOCAL, fixture.viewModel.readerStateStateFlow.value.preferredDisplayMode)
    }

    @Test fun `remembered server mode overrides local cache and server selection is remembered`() = runTest(dispatcher) {
        val a = article("ServerMode", fullContent = true)
        val fixture = fixture(a)
        whenever(fixture.cache.readFullContent(a.article.id, 1)).thenReturn(Result.success("<p>Cached local full text.</p>"))
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.renderServerContent(); runCurrent()
        assertEquals(SubscriptionDisplayMode.SERVER, fixture.feeds[a.feed.id]!!.selectedDisplayMode)
        assertFalse(fixture.feeds[a.feed.id]!!.isFullContent)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        assertEquals(SubscriptionDisplayMode.SERVER, fixture.viewModel.readerStateStateFlow.value.preferredDisplayMode)
    }

    @Test fun `original mode displays a distinct exact feed cache and later openings follow it`() = runTest(dispatcher) {
        val a = article("OriginalMode")
        val fixture = fixture(a)
        val body = "<p>Original RSS with a deliberately shorter summary.</p>"
        whenever(fixture.cache.readOriginalFeedContent(eq(a.article), any())).thenReturn(Result.success(body))
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.renderOriginalContent(); runCurrent()
        assertEquals(SubscriptionDisplayMode.ORIGINAL, fixture.feeds[a.feed.id]!!.selectedDisplayMode)
        assertEquals(ReaderState.Description(body, originalFeed = true), fixture.viewModel.readerStateStateFlow.value.content)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(ReaderState.Description(body, originalFeed = true), fixture.viewModel.readerStateStateFlow.value.content)
        verify(fixture.cache, never()).readOrFetchFullContent(any<Article>(), any())
    }

    @Test fun `missing original preserves useful server text and remembered choice`() = runTest(dispatcher) {
        val a = article("MissingOriginal")
        val fixture = fixture(a)
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.renderOriginalContent(); runCurrent()
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        assertEquals(SubscriptionDisplayMode.ORIGINAL, fixture.feeds[a.feed.id]!!.selectedDisplayMode)
        assertTrue(fixture.viewModel.readerStateStateFlow.value.feedSourceMessage!!.contains("unavailable"))
    }

    @Test fun `late original response cannot replace a later server selection`() = runTest(dispatcher) {
        val a = article("LateOriginal")
        val fixture = fixture(a)
        val result = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readOrFetchOriginalFeedContent(eq(a.article), any(), any())).thenSuspend {
            withContext(NonCancellable) { result.await() }
        }
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.renderOriginalContent(); runCurrent()
        fixture.viewModel.renderServerContent(); runCurrent()
        result.complete(Result.success("<p>Stale original</p>")); runCurrent()
        assertEquals(SubscriptionDisplayMode.SERVER, fixture.feeds[a.feed.id]!!.selectedDisplayMode)
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `late original response cannot replace another account or a newsletter policy`() = runTest(dispatcher) {
        val a = article("OriginalAccount")
        val fixture = fixture(a)
        val result = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readOrFetchOriginalFeedContent(eq(a.article), any(), any())).thenSuspend {
            withContext(NonCancellable) { result.await() }
        }
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.renderOriginalContent(); runCurrent()
        fixture.feeds[a.feed.id] = fixture.feeds[a.feed.id]!!.copy(isNewsletter = true)
        fixture.accountId.value = 2
        result.complete(Result.success("<p>Old account RSS</p>")); runCurrent()
        assertFalse(fixture.viewModel.readerStateStateFlow.value.content.text.orEmpty().contains("Old account RSS"))
    }

    @Test fun `legacy and newsletter sources never start original network fetching`() = runTest(dispatcher) {
        val legacy = article("Legacy")
        val newsletter = article("NewsletterOriginal").let { it.copy(feed = it.feed.copy(isNewsletter = true, displayMode = "original")) }
        val fixture = fixture(legacy, newsletter)
        fixture.viewModel.initData(legacy.article.id); runCurrent()
        fixture.viewModel.initData(newsletter.article.id); runCurrent()
        verify(fixture.cache, never()).readOriginalFeedContent(any(), any())
        verify(fixture.cache, never()).readOrFetchOriginalFeedContent(any(), any(), any())
        assertTrue(fixture.viewModel.readerStateStateFlow.value.isNewsletter)
    }

    @Test fun `private original mode allows cached content but requires explicit renewed network choice`() = runTest(dispatcher) {
        val a = article("PrivateOriginal").let { it.copy(feed = it.feed.copy(displayMode = "original", url = "https://example.com/rss?token=synthetic")) }
        val fixture = fixture(a)
        fixture.viewModel.initData(a.article.id); runCurrent()
        verify(fixture.cache, never()).readOrFetchOriginalFeedContent(any(), any(), any())
        assertTrue(fixture.viewModel.readerStateStateFlow.value.feedSourceMessage!!.contains("Choose Original again"))
        whenever(fixture.cache.readOrFetchOriginalFeedContent(any(), any(), any())).thenReturn(Result.success("<p>Explicit RSS</p>"))
        fixture.viewModel.renderOriginalContent(); runCurrent()
        assertEquals(ReaderState.Description("<p>Explicit RSS</p>", originalFeed = true), fixture.viewModel.readerStateStateFlow.value.content)
    }

    @Test fun `local account original mode uses its native RSS body without fetching`() = runTest(dispatcher) {
        val a = article("LocalOriginal")
        val fixture = fixture(a, local = true)
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.renderOriginalContent(); runCurrent()
        assertEquals(SubscriptionDisplayMode.ORIGINAL, fixture.feeds[a.feed.id]!!.selectedDisplayMode)
        assertEquals(ReaderState.Description(a.article.rawDescription, originalFeed = true), fixture.viewModel.readerStateStateFlow.value.content)
        verify(fixture.cache, never()).readOriginalFeedContent(any(), any())
        verify(fixture.cache, never()).readOrFetchOriginalFeedContent(any(), any(), any())
    }

    @Test fun `cached original cannot mount after current feed becomes newsletter`() = runTest(dispatcher) {
        val a = article("CachedOriginalPrivacy")
        val fixture = fixture(a)
        val cached = CompletableDeferred<Result<String>>()
        whenever(fixture.cache.readOriginalFeedContent(eq(a.article), any())).thenSuspend {
            withContext(NonCancellable) { cached.await() }
        }
        fixture.viewModel.initData(a.article.id); runCurrent()
        fixture.viewModel.renderOriginalContent(); runCurrent()
        fixture.feeds[a.feed.id] = fixture.feeds[a.feed.id]!!.copy(isNewsletter = true)
        cached.complete(Result.success("<p>Old cached RSS</p>")); runCurrent()
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        verify(fixture.cache, never()).readOrFetchOriginalFeedContent(any(), any(), any())
    }

    @Test fun `server selection settings failure retains current body and omits raw diagnostics`() = runTest(dispatcher) {
        val a = article("ServerSettingsFailure")
        val fixture = fixture(a)
        fixture.viewModel.initData(a.article.id); runCurrent()
        whenever(fixture.repository.findFeedById(a.feed.id)).thenThrow(IllegalStateException("sensitive fixture diagnostic"))
        fixture.viewModel.renderServerContent(); runCurrent()
        assertEquals(ReaderState.Description(a.article.rawDescription), fixture.viewModel.readerStateStateFlow.value.content)
        assertTrue(fixture.viewModel.readerStateStateFlow.value.feedSourceMessage!!.contains("unchanged"))
        assertFalse(fixture.viewModel.readerStateStateFlow.value.feedSourceMessage!!.contains("sensitive"))
    }

    private val visaRss = "After my memoir, “Huna,” was nominated, I made plans to attend the ceremony in New York—only to be found ineligible for a visitor visa."
    private val visaUrl = "https://www.newyorker.com/books/page-turner/i-am-a-2026-national-book-award-finalist-the-us-denied-my-visa"
    private fun visaBody(paragraphs: Int = 23) = (0 until paragraphs).joinToString("") {
        if (it == 0) "<p>$visaRss</p>" else "<p>Public visible paragraph $it. ${"The article continues with its original editorial prose. ".repeat(4)}</p>"
    }
    private fun visaArticle(mode: String? = null, newsletter: Boolean = false) = article("Visa").let {
        it.copy(article = it.article.copy(rawDescription = visaRss, link = visaUrl),
            feed = it.feed.copy(displayMode = mode, isFullContent = mode == "local", isNewsletter = newsletter,
                url = "https://www.newyorker.com/feed/everything/rss"))
    }
    private fun visaCache(rss: RssHelper) = ReaderCacheHelper(
        mock<Context> { on { cacheDir } doReturn temporary.root; on { filesDir } doReturn temporary.root }, dispatcher, rss, mock(),
    )
    private fun seedVisaCache(a: ArticleWithFeed, body: String) {
        temporary.root.resolve("readability/${a.article.accountId}/${readerCacheFileName(a.article.id)}")
            .apply { parentFile.mkdirs(); writeText(body) }
    }
    private fun assertVisaFullBody(fixture: Fixture) {
        val state = fixture.viewModel.readerStateStateFlow.value
        assertEquals(SubscriptionDisplayMode.LOCAL, state.preferredDisplayMode)
        assertTrue(state.content is ReaderState.FullContent)
        assertEquals(visaBody(), state.content.documentBody)
        assertEquals(23, Jsoup.parse(state.content.documentBody!!).select("p").size)
    }

    @Test fun `exact reported RSS changes to full article when Local is chosen from Server or Original`() = runTest(dispatcher) {
        val a = visaArticle()
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(visaUrl, a.article.title)).thenReturn(visaBody())
        whenever(rss.readOriginalFeedContent(a.feed.url, visaUrl)).thenReturn(visaRss)
        val fixture = fixture(a, cacheOverride = visaCache(rss))
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(visaRss, fixture.viewModel.readerStateStateFlow.value.content.documentBody)
        fixture.viewModel.renderServerContent(); runCurrent()
        verify(rss, never()).parseFullContent(any(), any())
        fixture.viewModel.renderFullContent(); runCurrent()
        assertVisaFullBody(fixture)
        fixture.viewModel.renderOriginalContent(); runCurrent()
        assertEquals(ReaderState.Description(visaRss, originalFeed = true), fixture.viewModel.readerStateStateFlow.value.content)
        fixture.viewModel.renderFullContent(); runCurrent()
        assertVisaFullBody(fixture)
        fixture.viewModel.renderServerContent(); runCurrent()
        fixture.viewModel.renderFullContent(); runCurrent()
        assertVisaFullBody(fixture)
        assertEquals(visaRss, fixture.viewModel.readingUiState.value.articleWithFeed!!.article.rawDescription)
        verify(rss, times(1)).parseFullContent(any(), any())
        verify(rss, times(1)).readOriginalFeedContent(any(), any())
    }

    @Test fun `initData upgrades cached RSS-only local result while keeping the summary until full body is ready`() = runTest(dispatcher) {
        val a = visaArticle("local")
        seedVisaCache(a, visaRss)
        val rss: RssHelper = mock()
        val response = CompletableDeferred<String>()
        whenever(rss.parseFullContent(any(), any())).thenSuspend { response.await() }
        val cache = visaCache(rss)
        val fixture = fixture(a, cacheOverride = cache)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(visaRss, fixture.viewModel.readerStateStateFlow.value.content.documentBody)
        response.complete(visaBody()); runCurrent()
        assertVisaFullBody(fixture)
        assertEquals(visaRss, cache.readPreviousContent(a.article.id, 1, false).getOrThrow())
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertVisaFullBody(fixture)
        verify(rss, times(1)).parseFullContent(any(), any())
    }

    @Test fun `offline old local article remains displayed without an automatic retry loop and explicit Retry stays available`() = runTest(dispatcher) {
        val a = visaArticle("local")
        val old = visaBody(9)
        seedVisaCache(a, old)
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenThrow(java.io.IOException("Offline"))
        val fixture = fixture(a, cacheOverride = visaCache(rss))
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(ReaderState.FullContent(old, refreshFailed = true), fixture.viewModel.readerStateStateFlow.value.content)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(old, fixture.viewModel.readerStateStateFlow.value.content.documentBody)
        verify(rss, times(1)).parseFullContent(any(), any())
        fixture.viewModel.renderFullContent(forceRefresh = true); runCurrent()
        assertEquals(ReaderState.FullContent(old, refreshFailed = true), fixture.viewModel.readerStateStateFlow.value.content)
        verify(rss, times(2)).parseFullContent(any(), any())
    }

    @Test fun `server original and newsletter openings never upgrade a cached publisher source`() = runTest(dispatcher) {
        val rss: RssHelper = mock()
        whenever(rss.readOriginalFeedContent(any(), any())).thenReturn(visaRss)
        for (a in listOf(visaArticle("server"), visaArticle("original"), visaArticle("local", newsletter = true))) {
            seedVisaCache(a, visaBody(9))
            val fixture = fixture(a, cacheOverride = visaCache(rss))
            fixture.viewModel.initData(a.article.id); runCurrent()
            assertEquals(visaRss, fixture.viewModel.readerStateStateFlow.value.content.documentBody)
            if (a.feed.isNewsletter) { fixture.viewModel.renderFullContent(); runCurrent() }
        }
        verify(rss, never()).parseFullContent(any(), any())
    }

    @Test fun `late automatic legacy upgrade cannot replace an explicit Server choice or certify its old cache`() = runTest(dispatcher) {
        val a = visaArticle("local")
        val old = visaBody(9)
        seedVisaCache(a, old)
        val rss: RssHelper = mock()
        val response = CompletableDeferred<String>()
        whenever(rss.parseFullContent(any(), any())).thenSuspend {
            withContext(NonCancellable) { response.await() }
        }
        val cache = visaCache(rss)
        val fixture = fixture(a, cacheOverride = cache)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(old, fixture.viewModel.readerStateStateFlow.value.content.documentBody)
        fixture.viewModel.renderServerContent(); runCurrent()
        assertEquals(ReaderState.Description(visaRss), fixture.viewModel.readerStateStateFlow.value.content)
        response.complete(visaBody()); runCurrent()
        assertEquals(ReaderState.Description(visaRss), fixture.viewModel.readerStateStateFlow.value.content)
        assertEquals(SubscriptionDisplayMode.SERVER, fixture.feeds[a.feed.id]!!.selectedDisplayMode)
        assertEquals(old, cache.readFullContent(a.article.id, 1).getOrThrow())
        assertTrue(cache.requiresNewYorkerCacheUpgrade(a.article, old))
    }

    @Test fun `forced Local refresh replaces a short server summary with enclosure only after retaining split header photo`() = runTest(dispatcher) {
        // FreshRSS compatibility adds an enclosure photo to the actual short RSS description.
        // This tests both cache-refresh and reader-display quality decisions, with the real parser.
        val serverFeed = visaRss + "<figure class='enclosure'><p class='enclosure-content'>" +
            "<img class='enclosure-thumbnail' src='https://reader.example.org/article-img-proxy.php?url=photo.jpg' " +
            "data-original-proxied-src='https://media.example.org/photo.jpg'></p></figure>"
        val base = visaArticle("server")
        val a = base.copy(article = base.article.copy(rawDescription = serverFeed))
        seedVisaCache(a, serverFeed)
        val publisherPage = "<html><head><title>Public article</title>" +
            "<script type='application/ld+json'>{\"@type\":\"NewsArticle\",\"url\":\"$visaUrl\",\"isAccessibleForFree\":true}</script>" +
            "</head><body><header data-testid='SplitScreenContentHeaderWrapper'>" +
            "<div data-testid='ContentHeaderLeadAsset'><span class='responsive-asset'><picture>" +
            "<img src='/photo.jpg'></picture></span></div><div data-testid='caption-wrapper'>Editorial photo credit.</div></header>" +
            "<div data-testid='BodyWrapper'><div class='body__inner-container'>" +
            visaBody().replace("<p>", "<p class='paywall'>") + "</div></div></body></html>"
        val rss: RssHelper = mock()
        whenever(rss.parseFullContent(any(), any())).thenAnswer {
            val prepared = ArticleExtractionPreparation.prepareArticleOrThrow(publisherPage, visaUrl)
            checkNotNull(ModernReadability().extract(prepared.html, visaUrl, preservedContent = prepared.articleHtml)).content
        }
        val cache = visaCache(rss)
        val fixture = fixture(a, cacheOverride = cache)
        fixture.viewModel.initData(a.article.id); runCurrent()
        assertEquals(serverFeed, fixture.viewModel.readerStateStateFlow.value.content.documentBody)
        fixture.viewModel.renderFullContent(forceRefresh = true); runCurrent()
        val state = fixture.viewModel.readerStateStateFlow.value
        assertEquals(SubscriptionDisplayMode.LOCAL, state.preferredDisplayMode)
        assertTrue(state.content is ReaderState.FullContent)
        val rendered = Jsoup.parseBodyFragment(state.content.documentBody!!)
        assertEquals(23, rendered.select("p").size)
        assertEquals(1, rendered.select("img").size)
        assertEquals("https://www.newyorker.com/photo.jpg", rendered.selectFirst("img")!!.attr("src"))
        assertEquals("Editorial photo credit.", rendered.selectFirst("figcaption")!!.text())
        assertEquals(serverFeed, fixture.viewModel.readingUiState.value.articleWithFeed!!.article.rawDescription)
        assertEquals(serverFeed, cache.readPreviousContent(a.article.id, 1, false).getOrThrow())
        verify(rss, times(1)).parseFullContent(any(), any())
    }

    private fun fixture(
        vararg articles: ArticleWithFeed,
        applicationScheduler: TestCoroutineScheduler = dispatcher.scheduler,
        googleReader: Boolean = false,
        local: Boolean = false,
        cacheOverride: ReaderCacheHelper? = null,
    ): Fixture {
        val cache: ReaderCacheHelper = cacheOverride ?: mock()
        val history: FeedSourceHistory = mock()
        val repository: AbstractRssRepository = when {
            googleReader -> mock<GoogleReaderRssService>()
            local -> mock<LocalRssService>()
            else -> mock()
        }
        val rssService: RssService = mock()
        val accountService: AccountService = mock()
        val feeds = articles.associate { it.feed.id to it.feed }.toMutableMap()
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
                feeds[id] ?: article("Stub").feed.copy(id = id)
            }
            whenever(repository.rememberDisplayMode(any(), any())).thenAnswer { invocation ->
                val feed = invocation.getArgument<Feed>(0)
                val mode = invocation.getArgument<SubscriptionDisplayMode>(1)
                feeds[feed.id] = feed.copy(displayMode = mode.value, isFullContent = mode == SubscriptionDisplayMode.LOCAL, isBrowser = false)
                Unit
            }
            if (cacheOverride == null) {
                whenever(cache.requiresNewYorkerCacheUpgrade(any(), any())).thenReturn(false)
                whenever(cache.readOriginalFeedContent(any(), any())).thenReturn(Result.failure(FileNotFoundException()))
                whenever(cache.readOrFetchOriginalFeedContent(any(), any(), any())).thenReturn(Result.failure(FileNotFoundException()))
                whenever(cache.readFullContent(any(), any())).thenReturn(Result.failure(FileNotFoundException()))
                whenever(cache.readBrowserText(any(), any())).thenReturn(Result.failure(FileNotFoundException()))
                whenever(cache.readPreviousContent(any(), any(), any())).thenReturn(Result.failure(FileNotFoundException()))
            }
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
        return Fixture(viewModel, cache, repository, paging, diffs, accountId, history, feeds)
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
        val feeds: MutableMap<String, Feed>,
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
