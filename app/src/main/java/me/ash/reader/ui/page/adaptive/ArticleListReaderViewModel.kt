package me.ash.reader.ui.page.adaptive

import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.util.fastFirstOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import javax.inject.Inject
import kotlin.collections.any
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.android.AndroidImageDownloader
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.PullToLoadNextFeedPreference
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.html.ArticleRequiresBrowserException
import me.ash.reader.infrastructure.rss.ArticleContentSelection
import me.ash.reader.ui.page.home.flow.NewspaperViewportState
import timber.log.Timber

private const val TAG = "FlowViewModel"

@OptIn(FlowPreview::class)
@HiltViewModel()
class ArticleListReaderViewModel
@Inject
constructor(
    private val rssService: RssService,
    private val accountService: AccountService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    val diffMapHolder: DiffMapHolder,
    private val filterStateUseCase: FilterStateUseCase,
    private val groupWithFeedsListUseCase: GroupWithFeedsListUseCase,
    private val settingsProvider: SettingsProvider,
    private val readerCacheHelper: ReaderCacheHelper,
    val textToSpeechManager: TextToSpeechManager,
    private val imageDownloader: AndroidImageDownloader,
    private val articleListUseCase: ArticlePagingListUseCase,
    workManager: WorkManager,
) : ViewModel() {

    val flowUiState: StateFlow<FlowUiState?> =
        articleListUseCase.pagerFlow
            .combine(groupWithFeedsListUseCase.groupWithFeedListFlow) {
                pagerData,
                groupWithFeedsList ->
                val filterState = pagerData.filterState
                var nextFilterState: FilterState? = null
                if (filterState.group != null) {
                    val groupList = groupWithFeedsList.map { it.group }
                    val index = groupList.indexOfFirst { it.id == filterState.group.id }
                    if (index != -1) {
                        val nextGroup = groupList.getOrNull(index + 1)
                        if (nextGroup != null) {
                            nextFilterState = filterState.copy(group = nextGroup)
                        }
                    } else {
                        val allGroupList =
                            rssService.get().queryAllGroupWithFeeds().map { it.group }
                        val index = allGroupList.indexOfFirst { it.id == filterState.group.id }
                        if (index != -1) {
                            val nextGroup =
                                allGroupList.subList(index, allGroupList.size).fastFirstOrNull {
                                    groupList.map { it.id }.contains(it.id)
                                }
                            if (nextGroup != null) {
                                nextFilterState = filterState.copy(group = nextGroup)
                            }
                        }
                    }
                } else if (filterState.feed != null) {
                    val feedList = groupWithFeedsList.flatMap { it.feeds }
                    val index = feedList.indexOfFirst { it.id == filterState.feed.id }
                    if (index != -1) {
                        val nextFeed = feedList.getOrNull(index + 1)
                        if (nextFeed != null) {
                            nextFilterState = filterState.copy(feed = nextFeed)
                        }
                    } else {
                        val allFeedList =
                            rssService.get().queryAllGroupWithFeeds().flatMap { it.feeds }
                        val index = allFeedList.indexOfFirst { it.id == filterState.feed.id }
                        if (index != -1) {
                            val nextFeed =
                                allFeedList.subList(index, allFeedList.size).fastFirstOrNull {
                                    feedList.map { it.id }.contains(it.id)
                                }
                            if (nextFeed != null) {
                                nextFilterState = filterState.copy(feed = nextFeed)
                            }
                        }
                    }
                }
                FlowUiState(nextFilterState = nextFilterState, pagerData = pagerData)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val syncWorkerStatusFlow =
        workManager
            .getWorkInfosByTagFlow(SyncWorker.SYNC_TAG)
            .map { it.any { workInfo -> workInfo.state == WorkInfo.State.RUNNING } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isSyncingFlow = MutableStateFlow(false)
    val isSyncingFlow = _isSyncingFlow.asStateFlow()

    init {
        viewModelScope.launch {
            syncWorkerStatusFlow.debounce(500L).collect { _isSyncingFlow.value = it }
        }
        // Re-run prefetchArticleId() when the paging snapshot list changes so that
        // nextArticle/previousArticle stay fresh (list can change due to sync,
        // read-status updates removing items from "Unread" filter, etc.).
        viewModelScope.launch {
            snapshotFlow { articleListUseCase.itemSnapshotList }
                .drop(1) // skip the initial emission — initData handles first prefetch
                .debounce(300L) // avoid rapid-fire during paging loads
                .collect {
                    val currentArticleId = _readerState.value.articleId
                    if (currentArticleId != null) {
                        _readerState.update { it.prefetchArticleId() }
                    }
                }
        }
    }

    fun importBrowserText(text: String) {
        val article = currentArticle ?: return
        articleLoadJob?.cancel()
        fullContentJob?.cancel()
        fullContentJob = viewModelScope.launch {
            val result = readerCacheHelper.importBrowserText(article.id, article.accountId, text)
            val hasPrevious = readerCacheHelper.readPreviousContent(article.id, article.accountId, true).isSuccess
            ensureActive()
            if (currentArticle?.id != article.id || currentArticle?.accountId != article.accountId) return@launch
            _readerState.update { state -> result.fold(
                onSuccess = { state.copy(content = ReaderState.FullContent(it, browserText = true),
                    hasBrowserText = true, hasPreviousBrowserText = hasPrevious, browserTextError = null) },
                onFailure = { state.copy(browserTextError = it.message ?: "Could not save browser text") },
            ) }
        }
    }

    fun renderBrowserText() {
        val article = currentArticle ?: return
        articleLoadJob?.cancel()
        fullContentJob?.cancel()
        fullContentJob = viewModelScope.launch {
            val result = readerCacheHelper.readBrowserText(article.id, article.accountId)
            ensureActive()
            if (currentArticle?.id != article.id || currentArticle?.accountId != article.accountId) return@launch
            _readerState.update { state -> result.fold(
                onSuccess = { state.copy(content = ReaderState.FullContent(it, browserText = true),
                    hasBrowserText = true, browserTextError = null) },
                onFailure = { state.copy(browserTextError = "Imported browser text is unavailable") },
            ) }
        }
    }

    fun renderPreviousFullContent() = renderPreviousSource(false)
    fun renderPreviousBrowserText() = renderPreviousSource(true)

    private fun renderPreviousSource(browserText: Boolean) {
        val article = currentArticle ?: return
        articleLoadJob?.cancel()
        fullContentJob?.cancel()
        fullContentJob = viewModelScope.launch {
            val currentBody = _readerState.value.content.documentBody
            val result = readerCacheHelper.readEarlierContent(article.id, article.accountId, browserText, currentBody)
            ensureActive()
            if (currentArticle?.id != article.id || currentArticle?.accountId != article.accountId) return@launch
            _readerState.update { state -> result.fold(
                onSuccess = { state.copy(content = ReaderState.FullContent(it, browserText = browserText, previousVersion = true), browserTextError = null) },
                onFailure = { state.copy(browserTextError = "Earlier source is unavailable; current content is unchanged") },
            ) }
        }
    }

    fun updateReadStatus(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        conditions: MarkAsReadConditions,
        isUnread: Boolean,
    ) {
        // Capture the cutoff once for both the retained edition and the database operation.
        val before = conditions.toDate()
        val target = ReadStatusScope(groupId, feedId, articleId, before?.time)
        newspaperViewportState.items.filterIsInstance<ArticleFlowItem.Article>().forEach {
            val item = it.articleWithFeed
            if (target.matches(item.article.id, item.article.feedId, item.feed.groupId, item.article.date.time)) {
                // A retained story may still have an opposite pending toggle. Reconcile that
                // exact existing diff so it cannot override this newer explicit bulk action.
                val pending = diffMapHolder.diffMap[item.article.id]
                if (pending != null && pending.isUnread != isUnread) {
                    diffMapHolder.updateDiff(item, isUnread = isUnread)
                }
                newspaperViewportState.recordReadStatus(item.article.id, isUnread)
            }
        }
        applicationScope.launch(ioDispatcher) {
            rssService
                .get()
                .markAsRead(
                    groupId = groupId,
                    feedId = feedId,
                    articleId = articleId,
                    before = before,
                    isUnread = isUnread,
                )
        }
    }

    fun updateStarredStatus(articleId: String?, isStarred: Boolean) {
        articleId?.let { newspaperViewportState.recordStarredStatus(it, isStarred) }
        applicationScope.launch(ioDispatcher) {
            if (articleId != null) {
                rssService.get().markAsStarred(articleId = articleId, isStarred = isStarred)
            }
        }
    }

    fun markAsReadFromListByDate(date: Date, isBefore: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val items =
                articleListUseCase.itemSnapshotList
                    .filterIsInstance<ArticleFlowItem.Article>()
                    .map { it.articleWithFeed }
                    .filter {
                        if (isBefore) {
                            date > it.article.date && it.article.isUnread
                        } else {
                            date < it.article.date && it.article.isUnread
                        }
                    }
                    .distinctBy { it.article.id }

            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
        }
    }

    fun loadNextFeedOrGroup() {
        viewModelScope.launch {
            if (
                settingsProvider.settings.pullToSwitchFeed ==
                    PullToLoadNextFeedPreference.MarkAsReadAndLoadNextFeed
            ) {
                markAllAsRead()
            }
            flowUiState.value?.nextFilterState?.let { filterStateUseCase.updateFilterState(it) }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            val items =
                articleListUseCase.itemSnapshotList.items
                    .filterIsInstance<ArticleFlowItem.Article>()
                    .map { it.articleWithFeed }

            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
        }
    }

    fun sync() {
        diffMapHolder.commitDiffsToDb()
        viewModelScope.launch {
            _isSyncingFlow.value = true
            val isSyncing = syncWorkerStatusFlow.value
            if (!isSyncing) {
                delay(1000L)
                if (syncWorkerStatusFlow.value == false) {
                    _isSyncingFlow.value = false
                }
            }
        }
        applicationScope.launch(ioDispatcher) {
            val filterState = filterStateUseCase.filterStateFlow.value
            val service = rssService.get()
            when (service) {
                is LocalRssService ->
                    service.doSyncOneTime(
                        feedId = filterState.feed?.id,
                        groupId = filterState.group?.id,
                    )

                is GoogleReaderRssService ->
                    service.doSyncOneTime(
                        feedId = filterState.feed?.id,
                        groupId = filterState.group?.id,
                    )

                else -> service.doSyncOneTime()
            }
        }
    }

    fun resetFilter() =
        filterStateUseCase.updateFilterState(feed = null, group = null, searchContent = null)

    fun changeFilter(filterState: FilterState) {
        filterStateUseCase.updateFilterState(
            filterState.feed,
            filterState.group,
            filterState.filter,
        )
    }

    fun inputSearchContent(content: String? = null) {
        if (content != filterStateUseCase.filterStateFlow.value.searchContent)
            filterStateUseCase.updateFilterState(searchContent = content)
    }

    private val _readingUiState = MutableStateFlow(ReadingUiState())
    val readingUiState: StateFlow<ReadingUiState> = _readingUiState.asStateFlow()

    private val _readerState: MutableStateFlow<ReaderState> = MutableStateFlow(ReaderState())
    val readerStateStateFlow = _readerState.asStateFlow()

    internal val newspaperViewportState = NewspaperViewportState()
    private val readerArticleSequence = ReaderArticleSequence<ArticleWithFeed> { it.article.id }

    private var articleLoadJob: Job? = null
    private var fullContentJob: Job? = null

    private val currentArticle: Article?
        get() = readingUiState.value.articleWithFeed?.article

    private val currentFeed: Feed?
        get() = readingUiState.value.articleWithFeed?.feed

    /** Called by the paged list before navigation, including stories retained after becoming read. */
    internal fun captureReaderSequence(pagerIdentity: Any, items: List<ArticleFlowItem>) {
        readerArticleSequence.update(pagerIdentity,
            items.filterIsInstance<ArticleFlowItem.Article>().map {
                if (newspaperViewportState.ownsPager(pagerIdentity)) newspaperViewportState.currentArticle(it.articleWithFeed)
                else it.articleWithFeed
            })
    }

    fun initData(articleId: String, listIndex: Int? = null, expectedAccountId: Int? = null) {
        articleLoadJob?.cancel()
        fullContentJob?.cancel()
        // Claim the visible reader immediately, including a suspended database lookup.
        // Hardware keys must not fall through to the article list while this loads.
        _readingUiState.value = ReadingUiState()
        _readerState.value = ReaderState(articleId = articleId)
        articleLoadJob = viewModelScope.launch {
            if (expectedAccountId != null && accountService.currentAccountIdFlow.value != expectedAccountId) {
                _readerState.update { it.copy(content = ReaderState.Error("Switch to this article's original RSS account to open its highlight.")) }
                return@launch
            }
            val snapshotList = articleListUseCase.itemSnapshotList
            val pagerIdentity = articleListUseCase.pagerFlow.value.pager
            readerArticleSequence.update(pagerIdentity, snapshotList.filterIsInstance<ArticleFlowItem.Article>().map { it.articleWithFeed })

            val itemByIndex =
                listIndex?.let { snapshotList.getOrNull(it) as? ArticleFlowItem.Article }

            // Resolution order:
            // 1. Item at listIndex if its ID matches the requested articleId
            // 2. Search the snapshot list by articleId
            // 3. The retained reader/edition identity
            // 4. Query the database
            val foundItem = when {
                itemByIndex != null && itemByIndex.articleWithFeed.article.id == articleId ->
                    itemByIndex.articleWithFeed
                else -> {
                    val found = snapshotList.find { item ->
                        item is ArticleFlowItem.Article &&
                            item.articleWithFeed.article.id == articleId
                    } as? ArticleFlowItem.Article
                    found?.articleWithFeed
                        ?: readerArticleSequence.find(pagerIdentity, articleId)
                        ?: rssService.get().findArticleById(articleId)
                }
            }
            val item = foundItem?.let {
                if (newspaperViewportState.ownsPager(pagerIdentity)) newspaperViewportState.currentArticle(it) else it
            }

            ensureActive()
            if (item == null) {
                _readerState.update {
                    it.copy(content = ReaderState.Error("Article not found"))
                }
                return@launch
            }

            if (expectedAccountId != null && (item.article.accountId != expectedAccountId ||
                    accountService.currentAccountIdFlow.value != expectedAccountId)) {
                _readerState.update { it.copy(content = ReaderState.Error("This highlight belongs to a different RSS account.")) }
                return@launch
            }
            if (diffMapHolder.checkIfUnread(item)) {
                diffMapHolder.updateDiff(item, isUnread = false)
            }
            newspaperViewportState.recordReadStatus(item.article.id, false)
            item.run {
                _readingUiState.update {
                    it.copy(articleWithFeed = this, isStarred = article.isStarred, isUnread = false)
                }
                val base = _readerState.value.copy(
                    articleId = article.id,
                    sourceAccountId = article.accountId,
                    feedName = feed.name,
                    title = article.title,
                    author = article.author,
                    link = article.link,
                    publishedDate = article.date,
                    content = ReaderState.Loading,
                    hasBrowserText = false, browserTextError = null,
                    hasPreviousFullContent = false, hasPreviousBrowserText = false,
                ).prefetchArticleId()
                _readerState.value = base
                val resolved = base.renderContent(this@run)
                ensureActive()
                _readerState.value = resolved
                if (resolved.content is ReaderState.Loading) renderFullContent()

            }
        }
    }

    fun clearReadingData() {
        readerArticleSequence.clear()
        articleLoadJob?.cancel()
        fullContentJob?.cancel()
        _readingUiState.update { ReadingUiState() }
        _readerState.update { ReaderState() }
    }

    suspend fun ReaderState.renderContent(articleWithFeed: ArticleWithFeed): ReaderState {
        val contentState =
            if (articleWithFeed.feed.isFullContent) {
                val fullContent =
                    readerCacheHelper.readFullContent(articleWithFeed.article.id, articleWithFeed.article.accountId).getOrNull()
                if (fullContent != null) {
                    withContext(ioDispatcher) { selectReaderContent(articleWithFeed.article.rawDescription, fullContent) }
                } else if (withContext(ioDispatcher) { ArticleContentSelection.isLikelyComplete(articleWithFeed.article.rawDescription) }) {
                    ReaderState.Description(articleWithFeed.article.rawDescription)
                } else {
                    ReaderState.Loading
                }
            } else {
                ReaderState.Description(articleWithFeed.article.rawDescription)
            }

        return copy(content = contentState, hasBrowserText = readerCacheHelper.readBrowserText(
            articleWithFeed.article.id, articleWithFeed.article.accountId).isSuccess,
            hasPreviousFullContent = readerCacheHelper.readPreviousContent(articleWithFeed.article.id, articleWithFeed.article.accountId, false).isSuccess,
            hasPreviousBrowserText = readerCacheHelper.readPreviousContent(articleWithFeed.article.id, articleWithFeed.article.accountId, true).isSuccess)
    }

    fun renderDescriptionContent() {
        articleLoadJob?.cancel()
        fullContentJob?.cancel()
        _readerState.update {
            it.copy(
                content = ReaderState.Description(content = currentArticle?.rawDescription ?: "")
            )
        }
    }

    fun renderFullContent(forceRefresh: Boolean = false) {
        val article = currentArticle ?: return
        articleLoadJob?.cancel()
        fullContentJob?.cancel()
        fullContentJob = viewModelScope.launch {
            val loadingJob = launch {
                delay(100L)
                // Keep an already readable source on screen during explicit retries. This avoids
                // destroying the reader's WebView/anchor while a network request is in flight.
                if (currentArticle?.id == article.id && currentArticle?.accountId == article.accountId &&
                    _readerState.value.content !is ReaderState.Description &&
                    _readerState.value.content !is ReaderState.FullContent) setLoading()
            }
            try {
                val result = if (forceRefresh) readerCacheHelper.refreshFullContent(article)
                    else readerCacheHelper.readOrFetchFullContent(article)
                val selected = withContext(ioDispatcher) {
                    result.fold(
                        onSuccess = { selectReaderContent(article.rawDescription, it) },
                        onFailure = { selectReaderContent(article.rawDescription, null, it) },
                    )
                }
                val hasPrevious = readerCacheHelper.readPreviousContent(article.id, article.accountId, false).isSuccess
                ensureActive()
                if (currentArticle?.id == article.id && currentArticle?.accountId == article.accountId) {
                    _readerState.update {
                        // A failed explicit refresh also keeps an already-open offline extraction.
                        val previous = it.content as? ReaderState.FullContent
                        it.copy(content = if (result.isFailure && previous != null)
                            previous.copy(refreshFailed = true, needsBrowser = result.exceptionOrNull() is ArticleRequiresBrowserException) else selected,
                            hasPreviousFullContent = hasPrevious)
                    }
                }
            } finally {
                loadingJob.cancel()
            }
        }
    }

    fun updateReadStatus(isUnread: Boolean) {
        val article = readingUiState.value.articleWithFeed ?: return
        diffMapHolder.updateDiff(article, isUnread = isUnread)
        newspaperViewportState.recordReadStatus(article.article.id, isUnread)
        _readingUiState.update {
            it.copy(isUnread = diffMapHolder.checkIfUnread(article))
        }
    }

    fun updateStarredStatus(isStarred: Boolean) {
        val article = currentArticle ?: return
        val service = rssService.get()
        newspaperViewportState.recordStarredStatus(article.id, isStarred)
        _readingUiState.update { it.copy(isStarred = isStarred) }
        applicationScope.launch(ioDispatcher) {
            service.markAsStarred(articleId = article.id, isStarred = isStarred)
        }
    }

    private fun setLoading() {
        _readerState.update { it.copy(content = ReaderState.Loading) }
    }

    fun ReaderState.prefetchArticleId(): ReaderState {
        val liveItems = articleListUseCase.itemSnapshotList
        val items = readerArticleSequence.update(articleListUseCase.pagerFlow.value.pager,
            liveItems.filterIsInstance<ArticleFlowItem.Article>().map { it.articleWithFeed })
        val currentId = articleId ?: return this
        val index = items.indexOfFirst { it.article.id == currentId }
        fun neighbour(offset: Int): ReaderState.PrefetchResult? =
            items.getOrNull(index + offset)?.takeIf { index >= 0 }?.let { item ->
                ReaderState.PrefetchResult(
                    articleId = item.article.id,
                    // This remains a lookup hint only; initData always validates its ID.
                    index = liveItems.indexOfFirst {
                        it is ArticleFlowItem.Article && it.articleWithFeed.article.id == item.article.id
                    },
                )
            }
        val liveIndex = liveItems.indexOfFirst {
            it is ArticleFlowItem.Article && it.articleWithFeed.article.id == currentId
        }
        return copy(
            nextArticle = neighbour(1), previousArticle = neighbour(-1), listIndex = liveIndex,
            articleOrdinal = (index + 1).takeIf { index >= 0 },
            totalArticleCount = items.size.takeIf { it > 0 },
        )
    }

    fun downloadImage(
        url: String,
        onSuccess: (Uri) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) {
        viewModelScope.launch {
            imageDownloader.downloadImage(url).onSuccess(onSuccess).onFailure(onFailure)
        }
    }
}

data class FlowUiState(val pagerData: PagerData, val nextFilterState: FilterState? = null)

data class ReadingUiState(
    val articleWithFeed: ArticleWithFeed? = null,
    val isUnread: Boolean = false,
    val isStarred: Boolean = false,
)

data class ReaderState(
    val articleId: String? = null,
    /** Account captured from the loaded article, never mutable global settings. */
    val sourceAccountId: Int? = null,
    val feedName: String = "",
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val content: ContentState = Loading,
    val listIndex: Int? = null,
    val nextArticle: PrefetchResult? = null,
    val previousArticle: PrefetchResult? = null,
    val articleOrdinal: Int? = null,
    val totalArticleCount: Int? = null,
    val hasBrowserText: Boolean = false,
    val hasPreviousFullContent: Boolean = false,
    val hasPreviousBrowserText: Boolean = false,
    val browserTextError: String? = null,
) {
    data class PrefetchResult(val articleId: String, val index: Int)

    sealed interface ContentState {
        /** Semantic DOM identity: status-only notices must not revoke annotation drafts. */
        val documentBody: String?
            get() = when (this) {
                is Description -> content
                is FullContent -> content
                is Error, Loading -> null
            }

        val text: String?
            get() {
                return when (this) {
                    is Description -> content
                    is Error -> message
                    is FullContent -> content
                    Loading -> null
                }
            }
    }

    data class FullContent(val content: String, val refreshFailed: Boolean = false,
        val browserText: Boolean = false, val needsBrowser: Boolean = false, val previousVersion: Boolean = false) : ContentState

    data class Description(val content: String, val fallback: Boolean = false, val needsBrowser: Boolean = false) : ContentState

    data class Error(val message: String) : ContentState

    data object Loading : ContentState
}

/** Shared by warm-cache and network paths, so offline reads receive the same quality decision. */
internal fun selectReaderContent(feed: String, extracted: String?, failure: Throwable? = null): ReaderState.ContentState {
    val selected = ArticleContentSelection.select(feed, extracted, failed = failure != null)
    return when (selected.source) {
        ArticleContentSelection.Source.FEED -> ReaderState.Description(selected.html.orEmpty(), fallback = true, needsBrowser = failure is ArticleRequiresBrowserException)
        ArticleContentSelection.Source.EXTRACTED -> ReaderState.FullContent(selected.html.orEmpty())
        ArticleContentSelection.Source.UNAVAILABLE -> ReaderState.Error(failure?.message ?: "No readable article content")
    }
}
