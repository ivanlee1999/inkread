package me.ash.reader.ui.page.home.flow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDateStickyHeader
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTonalElevation
import me.ash.reader.infrastructure.preference.LocalFlowFilterBarPadding
import me.ash.reader.infrastructure.preference.LocalFlowFilterBarStyle
import me.ash.reader.infrastructure.preference.LocalFlowFilterBarTonalElevation
import me.ash.reader.infrastructure.preference.LocalFlowTopBarTonalElevation
import me.ash.reader.infrastructure.preference.LocalMarkAsReadOnScroll
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.infrastructure.preference.LocalSettings
import me.ash.reader.infrastructure.preference.LocalSharedContent
import me.ash.reader.infrastructure.preference.LocalSortUnreadArticles
import me.ash.reader.infrastructure.preference.PullToLoadNextFeedPreference
import me.ash.reader.infrastructure.preference.SortUnreadArticlesPreference
import me.ash.reader.ui.component.FilterBar
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYExtensibleVisibility
import me.ash.reader.ui.component.base.newspaperSwipeScroll
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.scrollbar.VerticalScrollIndicatorFactory
import me.ash.reader.ui.component.scrollbar.drawVerticalScrollIndicator
import me.ash.reader.ui.component.scrollbar.scrollIndicator
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.motion.Direction
import me.ash.reader.ui.motion.sharedXAxisTransitionSlow
import me.ash.reader.ui.motion.sharedYAxisTransitionExpressive
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.home.reading.PullToLoadDefaults
import me.ash.reader.ui.page.home.reading.PullToLoadDefaults.ContentOffsetMultiple
import me.ash.reader.ui.page.home.reading.PullToLoadState
import me.ash.reader.ui.page.home.reading.pullToLoad
import me.ash.reader.ui.page.home.reading.rememberPullToLoadState

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
    ExperimentalMaterialApi::class,
)
@Composable
fun FlowPage(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    isTwoPane: Boolean,
    viewModel: ArticleListReaderViewModel,
    onNavigateUp: () -> Unit,
    navigateToArticle: (String, Int) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val articleListTonalElevation = LocalFlowArticleListTonalElevation.current
    val articleListFeedIcon = LocalFlowArticleListFeedIcon.current
    val articleListDateStickyHeader = LocalFlowArticleListDateStickyHeader.current
    val topBarTonalElevation = LocalFlowTopBarTonalElevation.current
    val filterBarStyle = LocalFlowFilterBarStyle.current
    val filterBarPadding = LocalFlowFilterBarPadding.current
    val filterBarTonalElevation = LocalFlowFilterBarTonalElevation.current
    val sharedContent = LocalSharedContent.current
    val markAsReadOnScroll = LocalMarkAsReadOnScroll.current.value
    val context = LocalContext.current

    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current

    val settings = LocalSettings.current
    val pullToSwitchFeed = settings.pullToSwitchFeed
    val einkMode = LocalEInkMode.current.isEInkMode()
    val haptic = LocalHapticFeedback.current


    val einkViewport = viewModel.newspaperViewportState
    val einkCurrentPage = einkViewport.currentPage
    // The retained edition is also the header's initial state when returning from the reader.
    val einkTotalPages = maxOf(1, einkViewport.pages.size)
    val einkArticleCount = remember(einkViewport.items) {
        einkViewport.items.count { it is ArticleFlowItem.Article }
    }

    val flowUiState = viewModel.flowUiState.collectAsStateValue()
    if (flowUiState == null) return

    val pagerData: PagerData = flowUiState.pagerData

    val filterUiState = pagerData.filterState

    val listState = rememberSaveable(pagerData, saver = LazyListState.Saver) { LazyListState(0, 0) }
    // A replacement feed/filter owns a fresh target; old geometry must never move its list.
    val articleRestoration = remember(pagerData.pager, listState) { NewspaperArticleRestoration() }
    // Keep the source and its identity together: a new filter must not use the old snapshot.
    var currentPagingItems by remember {
        mutableStateOf<Pair<Any, LazyPagingItems<ArticleFlowItem>>?>(null)
    }


    val isTopBarElevated = topBarTonalElevation.value > 0
    val scrolledTopBarContainerColor =
        with(MaterialTheme.colorScheme) { if (isTopBarElevated) surfaceContainer else surface }

    val titleText =
        when {
            filterUiState.group != null -> filterUiState.group.name
            filterUiState.feed != null -> filterUiState.feed.name
            else -> filterUiState.filter.toName()
        }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var markAsRead by remember { mutableStateOf(false) }
    var onSearch by rememberSaveable { mutableStateOf(false) }

    var currentPullToLoadState: PullToLoadState? by remember { mutableStateOf(null) }
    var currentLoadAction: LoadAction? by remember { mutableStateOf(null) }

    val settleSpec = remember { androidx.compose.animation.core.snap<Float>() }

    val lastVisibleIndex =
        remember(listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .filterNotNull()
        }

    val onToggleStarred: (ArticleWithFeed) -> Unit = remember {
        { article ->
            viewModel.updateStarredStatus(
                articleId = article.article.id,
                isStarred = !article.article.isStarred,
            )
        }
    }

    val onToggleRead: (ArticleWithFeed) -> Unit = remember {
        { articleWithFeed -> viewModel.diffMapHolder.updateDiff(articleWithFeed) }
    }

    val sortByEarliest =
        filterUiState.filter.isUnread() &&
            LocalSortUnreadArticles.current == SortUnreadArticlesPreference.Earliest

    val onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? =
        remember(sortByEarliest) {
            {
                viewModel.markAsReadFromListByDate(
                    date = it.article.date,
                    isBefore = sortByEarliest,
                )
            }
        }

    val onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? =
        remember(sortByEarliest) {
            {
                viewModel.markAsReadFromListByDate(
                    date = it.article.date,
                    isBefore = !sortByEarliest,
                )
            }
        }

    val onShare: ((ArticleWithFeed) -> Unit)? = remember {
        { articleWithFeed ->
            with(articleWithFeed.article) { sharedContent.share(context, title, link) }
        }
    }

    LaunchedEffect(onSearch) {
        if (!onSearch) {
            keyboardController?.hide()
            viewModel.inputSearchContent(null)
        }
    }

    val topAppBarState = rememberTopAppBarState()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    val scrollAppBarToCollapsed =
        remember(topAppBarState) {
            {
                scope.launch {
                    val initial = topAppBarState.heightOffset
                    val target = topAppBarState.heightOffsetLimit
                    if (initial != target)
                        animate(
                            initialValue = initial,
                            targetValue = target,
                            initialVelocity = 0f,
                            animationSpec = settleSpec,
                        ) { value, _ ->
                            topAppBarState.heightOffset = value
                        }
                }
            }
        }

    val snapAppBarToCollapsed =
        remember(topAppBarState) {
            {
                scope.launch {
                    val initial = topAppBarState.heightOffset
                    val target = topAppBarState.heightOffsetLimit
                    if (initial != target) {
                        topAppBarState.heightOffset = target
                    }
                }
            }
        }

    // In E-Ink mode, immediately collapse the large top app bar to reclaim whitespace
    LaunchedEffect(einkMode) {
        if (einkMode) snapAppBarToCollapsed()
    }

    // Buttons and hardware keys use exactly the same non-animated page/scroll change.
    val turnEinkPage: (Boolean) -> Boolean = { forward ->
        articleRestoration.cancel()
        if (!einkViewport.ownsPager(pagerData.pager)) false else einkViewport.turn(
            forward = forward,
            markReadEnabled = einkMode && markAsReadOnScroll && filterUiState.filter.isUnread(),
            onMarkRead = { articles ->
                viewModel.diffMapHolder.updateDiff(
                    articleWithFeed = articles.toTypedArray(), isUnread = false,
                )
            },
        )
    }
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()
    // ReadingPage's HIGH-priority consumer takes precedence in two-pane layouts.
    EInkVolumePageKeys(einkMode && !onSearch && !markAsRead && readerState.articleId == null, einkViewport, onTurn = { forward ->
        // initData establishes the reader synchronously, before its HIGH-priority consumer's
        // composition effect. Hardware must not mark a hidden list during that gap.
        if (onSearch || markAsRead || viewModel.readerStateStateFlow.value.articleId != null) false else turnEinkPage(forward)
    }) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val isSyncing = viewModel.isSyncingFlow.collectAsStateValue()
    var lastSyncTime by remember { mutableLongStateOf(0L) }
    var wasSyncing by remember { mutableStateOf(false) }
    var explicitEditionRefresh by remember { mutableStateOf(false) }
    var refreshEditionSource by remember { mutableStateOf(false) }
    LaunchedEffect(isSyncing) {
        if (wasSyncing && !isSyncing) {
            lastSyncTime = System.currentTimeMillis()
            if (einkMode && explicitEditionRefresh) {
                explicitEditionRefresh = false
                refreshEditionSource = true
            }
        }
        wasSyncing = isSyncing
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RYScaffold(
            reserveBottomBarSpace = true,
            containerTonalElevation = articleListTonalElevation.value.dp,
            topBar = {
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme,
                    typography =
                        MaterialTheme.typography.copy(
                            headlineMedium = MaterialTheme.typography.displaySmall,
                            titleLarge =
                                MaterialTheme.typography.titleLarge.merge(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                        ),
                ) {
                    if (einkMode) {
                        EInkArticleListHeader(
                            title = titleText,
                            filter = filterUiState.filter,
                            currentPage = einkCurrentPage + 1,
                            totalPages = einkTotalPages,
                            totalArticles = einkArticleCount,
                            isSyncing = isSyncing,
                            lastSyncTime = lastSyncTime,
                            navigationKey = pagerData.pager,
                            markAllSelected = markAsRead,
                            searchSelected = onSearch,
                            onNavigateUp = {
                                onSearch = false
                                onNavigateUp()
                            },
                            onFilterChange = { newFilter ->
                                markAsRead = false
                                viewModel.changeFilter(filterUiState.copy(filter = newFilter))
                                // The replacement pager resets the retained edition atomically.
                            },
                            onRefresh = { explicitEditionRefresh = true; viewModel.sync() },
                            onMarkAllAsRead = {
                                if (markAsRead) {
                                    markAsRead = false
                                } else {
                                    scope.launch {
                                        if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
                                        markAsRead = true
                                        onSearch = false
                                    }
                                }
                            },
                            onSearch = {
                                if (onSearch) {
                                    onSearch = false
                                } else {
                                    scope.launch {
                                        if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
                                        onSearch = true
                                        markAsRead = false
                                        delay(100)
                                        focusRequester.requestFocus()
                                    }
                                }
                            },
                            onPrev = { turnEinkPage(false) },
                            onNext = { turnEinkPage(true) },
                        )
                    } else {
                    LargeTopAppBar(
                        modifier =
                            Modifier.clickable(
                                onClick = {
                                    scope.launch {
                                        if (listState.firstVisibleItemIndex != 0) {
                                            listState.scrollToItem(0)
                                        }
                                    }
                                },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ),
                        title = {
                            val textStyle = LocalTextStyle.current
                            val color = LocalContentColor.current
                            if (textStyle.fontSize.value > 18f) {
                                BasicText(
                                    modifier =
                                        Modifier.padding(
                                            start = if (articleListFeedIcon.value) 34.dp else 8.dp,
                                            end = 24.dp,
                                        ),
                                    text = titleText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = textStyle,
                                    color = { color },
                                    autoSize =
                                        TextAutoSize.StepBased(
                                            minFontSize = 28.sp,
                                            maxFontSize = textStyle.fontSize,
                                        ),
                                )
                            } else {
                                Text(
                                    text = titleText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        expandedHeight = 172.dp,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            FeedbackIconButton(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.onSurface,
                            ) {
                                onSearch = false
                                onNavigateUp()
                            }
                        },
                        actions = {
                            RYExtensibleVisibility(animated = false, visible = !filterUiState.filter.isStarred()) {
                                FeedbackIconButton(
                                    imageVector = Icons.Rounded.DoneAll,
                                    contentDescription = stringResource(R.string.mark_all_as_read),
                                    tint =
                                        if (markAsRead) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                ) {
                                    if (markAsRead) {
                                        markAsRead = false
                                    } else {
                                        scope
                                            .launch {
                                                if (listState.firstVisibleItemIndex != 0) {
                                                    listState.scrollToItem(0)
                                                }
                                            }
                                            .invokeOnCompletion {
                                                markAsRead = true
                                                onSearch = false
                                            }
                                    }
                                }
                            }
                            FeedbackIconButton(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.search),
                                tint =
                                    if (onSearch) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            ) {
                                if (onSearch) {
                                    onSearch = false
                                } else {
                                    scope
                                        .launch {
                                            if (listState.firstVisibleItemIndex != 0) {
                                                listState.scrollToItem(0)
                                            }
                                        }
                                        .invokeOnCompletion {
                                            scope.launch {
                                                onSearch = true
                                                markAsRead = false
                                                delay(100)
                                                focusRequester.requestFocus()
                                            }
                                        }
                                }
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                scrolledContainerColor = scrolledTopBarContainerColor
                            ),
                    )
                    } // end else (non-eink LargeTopAppBar)
                }
            },
            content = {
                ArticleListPaneLayout { readingLayout ->
                    val newspaperColumns = readingLayout.columns
                    val renderedColumns = newspaperColumns
                    val currentColumns by rememberUpdatedState(renderedColumns)
                    val activePager by rememberUpdatedState(pagerData.pager)
                    val activeEinkMode by rememberUpdatedState(einkMode)
                    fun restorationLayoutKey(page: Int = einkCurrentPage): List<Any?> = listOf(
                        activePager, if (activeEinkMode) page else null,
                        currentColumns,
                    )
                    // Restore the selected article once when it loads. E-ink resizes separately preserve
                    // the current viewport, rather than jumping back to a previously selected article.
                    LaunchedEffect(
                        readerState.articleId, listState, pagerData.pager, einkMode,
                        if (einkMode) null else newspaperColumns,
                    ) {
                        articleRestoration.cancel()
                        // The e-ink edition stays at the exact page opened, even if the unread source has removed
                        // the selected story. Only continuous mode scrolls to the current reader article.
                        if (einkMode) return@LaunchedEffect
                        val articleId = readerState.articleId ?: return@LaunchedEffect
                        snapshotFlow {
                            currentPagingItems?.takeIf { it.first === pagerData.pager }?.second?.itemSnapshotList
                        }.first { snapshot ->
                            val index = snapshot?.indexOfFirst {
                                it is ArticleFlowItem.Article && it.articleWithFeed.article.id == articleId
                            } ?: -1
                            if (index != -1) {
                                snapAppBarToCollapsed()
                                val rows = newspaperRows(snapshot!!.size, currentColumns) { snapshot[it] is ArticleFlowItem.Date }
                                articleRestoration.request(NewspaperArticleRestoreTarget(
                                    articleId, index, rows.rowForSourceIndex(index), currentColumns, restorationLayoutKey(),
                                ), listState)
                                true
                            } else false
                        }
                    }

                RYExtensibleVisibility(animated = false, modifier = Modifier.zIndex(1f), visible = onSearch) {
                    BackHandler(onSearch) { onSearch = false }
                    SearchBar(
                        value = filterUiState.searchContent ?: "",
                        placeholder =
                            when {
                                filterUiState.group != null ->
                                    stringResource(
                                        R.string.search_for_in,
                                        filterUiState.filter.toName(),
                                        filterUiState.group.name,
                                    )

                                filterUiState.feed != null ->
                                    stringResource(
                                        R.string.search_for_in,
                                        filterUiState.filter.toName(),
                                        filterUiState.feed.name,
                                    )

                                else ->
                                    stringResource(
                                        R.string.search_for,
                                        filterUiState.filter.toName(),
                                    )
                            },
                        focusRequester = focusRequester,
                        onValueChange = { viewModel.inputSearchContent(it) },
                        onClose = {
                            onSearch = false
                            viewModel.inputSearchContent(null)
                        },
                    )
                }

                RYExtensibleVisibility(animated = false, visible = markAsRead) {
                    BackHandler(markAsRead) { markAsRead = false }

                    MarkAsReadBar {
                        markAsRead = false
                        viewModel.updateReadStatus(
                            groupId = filterUiState.group?.id,
                            feedId = filterUiState.feed?.id,
                            articleId = null,
                            conditions = it,
                            isUnread = false,
                        )
                    }
                }
                val contentTransitionVertical =
                    if (einkMode) EnterTransition.None togetherWith ExitTransition.None
                    else sharedYAxisTransitionExpressive(direction = Direction.Forward)
                val contentTransitionBackward =
                    if (einkMode) EnterTransition.None togetherWith ExitTransition.None
                    else sharedXAxisTransitionSlow(direction = Direction.Backward)
                val contentTransitionForward =
                    if (einkMode) EnterTransition.None togetherWith ExitTransition.None
                    else sharedXAxisTransitionSlow(direction = Direction.Forward)
                AnimatedContent(
                    targetState = flowUiState,
                    contentKey = { it.pagerData.filterState.copy(searchContent = null) },
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                ) { flowUiState ->
                    val pager = flowUiState.pagerData.pager
                    val filterState = flowUiState.pagerData.filterState
                    val pagingItems = pager.collectAsLazyPagingItems().also { currentPagingItems = pager to it }

                    val itemSnapshot = pagingItems.itemSnapshotList
                    LaunchedEffect(pagingItems, refreshEditionSource) {
                        if (refreshEditionSource) {
                            // Refresh the Paging generation after user-requested RSS sync, then
                            // replace the edition only with that completed generation. Background
                            // sync never resets a page the user is reading or returning to.
                            val refreshed = async(start = CoroutineStart.UNDISPATCHED) {
                                var loadingObserved = false
                                snapshotFlow { pagingItems.loadState.refresh }.first { load ->
                                    if (load is LoadState.Loading) loadingObserved = true
                                    loadingObserved && load !is LoadState.Loading
                                }
                            }
                            // Install the observer before refresh can publish its fast states.
                            pagingItems.refresh()
                            refreshed.await()
                            if (pagingItems.loadState.refresh is LoadState.NotLoading) {
                                einkViewport.reset()
                                einkViewport.acceptSnapshot(pager, pagingItems.itemSnapshotList.filterNotNull(), sortByEarliest)
                            }
                            refreshEditionSource = false
                        }
                    }
                    LaunchedEffect(pager, itemSnapshot, einkMode, einkViewport.revision) {
                        if (einkMode) einkViewport.acceptSnapshot(pager, itemSnapshot.filterNotNull(), sortByEarliest)
                    }
                    val einkStartIndex: Int? = null
                    val einkEndIndex: Int? = null
                    val visibleNewspaperRows = remember(itemSnapshot, renderedColumns) {
                        newspaperRows(itemSnapshot.size, renderedColumns) { itemSnapshot[it] is ArticleFlowItem.Date }
                    }

                    if (!einkMode && markAsReadOnScroll && filterState.filter.isUnread()) {
                        var userScrollStarted by remember(pager, newspaperColumns) { mutableStateOf(false) }
                        LaunchedEffect(listState.isScrollInProgress) {
                            if (listState.isScrollInProgress) {
                                userScrollStarted = articleRestoration.target == null
                            } else if (userScrollStarted && articleRestoration.target == null) {
                                userScrollStarted = false
                                val firstVisibleArticleRow =
                                    listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.contentType == CONTENT_TYPE_ARTICLE }
                                        ?.index ?: return@LaunchedEffect
                                val readRange = newspaperScrolledReadRange(
                                    visibleNewspaperRows, firstVisibleArticleRow,
                                    einkStartIndex ?: 0, einkEndIndex ?: pagingItems.itemCount,
                                    einkMode = einkMode,
                                )
                                val items = mutableListOf<ArticleWithFeed>()
                                for (index in readRange) {
                                    val item = pagingItems.peek(index)
                                    if (item is ArticleFlowItem.Article) {
                                        items.add(item.articleWithFeed)
                                    }
                                }
                                if (items.isNotEmpty()) {
                                    viewModel.diffMapHolder.updateDiff(
                                        articleWithFeed = items.toTypedArray(),
                                        isUnread = false,
                                    )
                                }
                            }
                        }
                    }

                    if (!einkMode && settings.flowArticleListDateStickyHeader.value) {
                        LaunchedEffect(lastVisibleIndex, visibleNewspaperRows) {
                            lastVisibleIndex.collect {
                                val sourceIndex = visibleNewspaperRows.getOrNull(it)?.indices?.lastOrNull()
                                if (sourceIndex != null && sourceIndex in (pagingItems.itemCount - 25 until pagingItems.itemCount)) {
                                    pagingItems[sourceIndex]
                                }
                            }
                        }
                    }

                    val listState = remember(pager) { listState }

                    val isSyncing by rememberUpdatedState(isSyncing)

                    LaunchedEffect(pagingItems) {
                        snapshotFlow { pagingItems.loadState.isIdle }
                            .collect {
                                if (isSyncing && !einkMode) {
                                    listState.scrollToItem(0)
                                }
                            }
                    }

                    val loadAction =
                        remember(pager, flowUiState, pullToSwitchFeed) {
                                when (pullToSwitchFeed) {
                                    PullToLoadNextFeedPreference.None -> null
                                    else -> {
                                        when {
                                            flowUiState.nextFilterState != null ->
                                                LoadAction.NextFeed.fromFilterState(
                                                    flowUiState.nextFilterState
                                                )

                                            filterState.filter.isUnread() &&
                                                pullToSwitchFeed ==
                                                    PullToLoadNextFeedPreference
                                                        .MarkAsReadAndLoadNextFeed ->
                                                LoadAction.MarkAllAsRead

                                            else -> null
                                        }
                                    }
                                }
                            }
                            .also { currentLoadAction = it }

                    val onLoadNext: (() -> Unit)? =
                        when (loadAction) {
                            is LoadAction.NextFeed -> viewModel::loadNextFeedOrGroup
                            LoadAction.MarkAllAsRead -> {
                                {
                                    viewModel.markAllAsRead()
                                    currentPullToLoadState?.animateDistanceTo(
                                        targetValue = 0f,
                                        animationSpec = settleSpec,
                                    )
                                }
                            }

                            else -> null
                        }

                    val onPullToSync: (() -> Unit)? =
                        if (isSyncing) null
                        else {
                            {
                                viewModel.sync()
                                currentPullToLoadState?.animateDistanceTo(
                                    targetValue = 0f,
                                    animationSpec = settleSpec,
                                )
                            }
                        }

                    val pullToLoadState =
                        rememberPullToLoadState(
                                key = pager,
                                onLoadNext = onLoadNext,
                                onLoadPrevious = onPullToSync,
                                loadThreshold = PullToLoadDefaults.loadThreshold(.1f),
                            )
                            .also { currentPullToLoadState = it }


                    val diffMap = viewModel.diffMapHolder.diffMap
                    val feedUnreadCounts by remember(pagingItems.itemCount, diffMap) {
                        derivedStateOf {
                            val counts = mutableMapOf<String, Int>()
                            for (i in 0 until pagingItems.itemCount) {
                                val item = pagingItems.peek(i)
                                if (item is ArticleFlowItem.Article) {
                                    val article = item.articleWithFeed.article
                                    val isUnread = diffMap[article.id]?.isUnread ?: article.isUnread
                                    if (isUnread) {
                                        val feedId = item.articleWithFeed.feed.id
                                        counts[feedId] = (counts[feedId] ?: 0) + 1
                                    }
                                }
                            }
                            counts
                        }
                    }

                    if (einkMode) {
                        EInkNewspaperViewport(
                            state = einkViewport,
                            columns = newspaperColumns,
                            horizontalPadding = readingLayout.horizontalPaddingDp.dp,
                            columnSpacing = readingLayout.columnSpacingDp.dp,
                            modifier = Modifier.fillMaxSize(),
                            diffMap = diffMap,
                            feedUnreadCounts = feedUnreadCounts,
                            articleListTonalElevation = articleListTonalElevation.value,
                            onTurn = turnEinkPage,
                            onClick = { articleWithFeed, editionIndex ->
                                if (articleWithFeed.feed.isBrowser) {
                                    viewModel.diffMapHolder.updateDiff(articleWithFeed, isUnread = false)
                                    context.openURL(articleWithFeed.article.link, openLink, openLinkSpecificBrowser)
                                } else {
                                    val liveIndex = itemSnapshot.indexOfFirst {
                                        it is ArticleFlowItem.Article &&
                                            it.articleWithFeed.article.id == articleWithFeed.article.id
                                    }
                                    viewModel.captureReaderSequence(pager, einkViewport.items)
                                    navigateToArticle(articleWithFeed.article.id, if (liveIndex >= 0) liveIndex else editionIndex)
                                }
                            },
                            onToggleStarred = { article ->
                                einkViewport.recordStarredStatus(article.article.id, !article.article.isStarred)
                                onToggleStarred(article)
                            },
                            onToggleRead = { article ->
                                einkViewport.recordReadStatus(article.article.id,
                                    !(diffMap[article.article.id]?.isUnread ?: article.article.isUnread))
                                onToggleRead(article)
                            },
                            onMarkAboveAsRead = onMarkAboveAsRead,
                            onMarkBelowAsRead = onMarkBelowAsRead,
                            onShare = onShare,
                            onDisplayed = { indices ->
                                // Resolve the shown edition IDs against the live pager before
                                // prefetching. Frozen indices are not mutable paging positions.
                                val displayedIds = indices.mapNotNull {
                                    (einkViewport.items.getOrNull(it) as? ArticleFlowItem.Article)?.articleWithFeed?.article?.id
                                }.toSet()
                                val liveIndex = itemSnapshot.indexOfLast {
                                    it is ArticleFlowItem.Article && it.articleWithFeed.article.id in displayedIds
                                }
                                if (liveIndex >= 0) pagingItems[liveIndex]
                                else if (einkViewport.currentPage == einkViewport.pages.lastIndex &&
                                    pagingItems.itemCount > 0 && pagingItems.loadState.append is LoadState.NotLoading &&
                                    !pagingItems.loadState.append.endOfPaginationReached) {
                                    // The entire retained unread page may have disappeared from
                                    // Room. Load from the actual live tail, never its stale indices.
                                    pagingItems[pagingItems.itemCount - 1]
                                }
                            },
                        )
                    } else {
                    LazyColumn(
                            // Page buttons remain instant; scrolling keeps oversized text/cards reachable.
                            userScrollEnabled = !einkMode,
                            contentPadding = PaddingValues(bottom = if (einkMode) 0.dp else 16.dp),
                            modifier =
                                Modifier.pullToLoad(
                                        state = pullToLoadState,
                                        enabled = !einkMode,
                                        contentOffsetY = { fraction ->
                                            if (fraction > 0f) {
                                                (fraction * ContentOffsetMultiple * 1.5f)
                                                    .dp
                                                    .roundToPx()
                                            } else {
                                                (fraction * ContentOffsetMultiple * 2f)
                                                    .dp
                                                    .roundToPx()
                                            }
                                        },
                                        onScroll = {
                                            if (it < -10f) {
                                                markAsRead = false
                                            }
                                        },
                                    )
                                    .let { if (!einkMode) it.nestedScroll(scrollBehavior.nestedScrollConnection) else it }
                                    .fillMaxSize()
                                    .drawVerticalScrollIndicator(listState),
                            state = listState,
                        ) {
                            ArticleList(
                                pagingItems = pagingItems,
                                diffMap = diffMap,
                                isShowFeedIcon = articleListFeedIcon.value,
                                isShowStickyHeader = articleListDateStickyHeader.value,
                                articleListTonalElevation = articleListTonalElevation.value,
                                isSwipeEnabled = { !listState.isScrollInProgress },
                                onClick = { articleWithFeed, index ->
                                    if (articleWithFeed.feed.isBrowser) {
                                        viewModel.diffMapHolder.updateDiff(
                                            articleWithFeed,
                                            isUnread = false,
                                        )
                                        context.openURL(
                                            articleWithFeed.article.link,
                                            openLink,
                                            openLinkSpecificBrowser,
                                        )
                                    } else {
                                        navigateToArticle(articleWithFeed.article.id, index)
                                    }
                                },
                                onToggleStarred = onToggleStarred,
                                onToggleRead = onToggleRead,
                                onMarkAboveAsRead = onMarkAboveAsRead,
                                onMarkBelowAsRead = onMarkBelowAsRead,
                                onShare = onShare,
                                einkPageStart = einkStartIndex,
                                einkPageEnd = einkEndIndex,
                                feedUnreadCounts = feedUnreadCounts,
                                columns = renderedColumns,
                                rowPlan = visibleNewspaperRows,
                                horizontalPadding = readingLayout.horizontalPaddingDp.dp,
                                columnSpacing = readingLayout.columnSpacingDp.dp,
                                restoreArticleId = articleRestoration.target?.articleId,
                                onArticlePositioned = { id, source, row, columns, offset ->
                                    val pinnedHeader = if (articleListDateStickyHeader.value) {
                                        listState.layoutInfo.visibleItemsInfo.filter {
                                            it.contentType == CONTENT_TYPE_DATE_HEADER
                                        }.maxOfOrNull { it.size } ?: 0
                                    } else 0
                                    articleRestoration.measured(id, source, row, columns,
                                        (offset - pinnedHeader).coerceAtLeast(0),
                                        restorationLayoutKey(), listState)
                                },
                            )

                        }
                    }
                }
                }
            },
            floatingActionButtonPosition = FabPosition.Center,
            bottomBar = {
                if (!einkMode) {
                    FilterBar(
                        filter = filterUiState.filter,
                        filterBarStyle = filterBarStyle.value,
                        filterBarFilled = true,
                        filterBarPadding = filterBarPadding.dp,
                        filterBarTonalElevation = filterBarTonalElevation.value.dp,
                    ) {
                        if (filterUiState.filter != it) {
                            viewModel.changeFilter(filterUiState.copy(filter = it))
                        } else {
                            scope.launch {
                                if (listState.firstVisibleItemIndex != 0) {
                                    listState.scrollToItem(0)
                                }
                            }
                        }
                    }
                }
            },
        )
        if (!einkMode) currentPullToLoadState?.let {
            PullToSyncIndicator(pullToLoadState = it, isSyncing = isSyncing)
            PullToLoadIndicator(
                state = it,
                loadAction = currentLoadAction,
                modifier =
                    Modifier.padding(bottom = 36.dp)
                        .windowInsetsPadding(
                            WindowInsets.safeContent.only(WindowInsetsSides.Horizontal)
                        ),
            )
        }
    }
}
