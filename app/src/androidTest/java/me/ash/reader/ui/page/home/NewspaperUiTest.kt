package me.ash.reader.ui.page.home

import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.decode.DataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.collectAsState
import me.ash.reader.ui.page.settings.color.ReadingLayoutPresetSetting
import me.ash.reader.infrastructure.preference.FlowArticleListImagePreference
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalReadingLayoutPreset
import me.ash.reader.infrastructure.preference.LocalReadingColumns
import me.ash.reader.infrastructure.preference.ReadingColumnsPreference
import me.ash.reader.infrastructure.preference.ReadingLayoutPresetPreference
import me.ash.reader.ui.layout.ReadingLayoutPreset
import me.ash.reader.ui.layout.resolveReadingLayout
import me.ash.reader.infrastructure.rss.ArticleImageEnrichment
import me.ash.reader.ui.ext.dataStore
import android.graphics.Bitmap
import android.graphics.Canvas
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalArticleListSwipeEndAction
import me.ash.reader.infrastructure.preference.SwipeEndActionPreference
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import me.ash.reader.ui.component.FilterBar
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.NewspaperMasthead
import me.ash.reader.ui.component.base.newspaperSwipeScroll
import me.ash.reader.ui.page.home.flow.EInkPaginationBar
import me.ash.reader.ui.page.home.flow.EInkArticlePageControl
import me.ash.reader.infrastructure.preference.FlowFilterBarStylePreference
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import me.ash.reader.ui.page.home.flow.NewspaperPageChange
import me.ash.reader.ui.page.home.flow.NewspaperArticleRestoration
import me.ash.reader.ui.page.home.flow.NewspaperArticleRestoreTarget
import me.ash.reader.ui.page.home.flow.newspaperPageTurn
import me.ash.reader.ui.page.home.flow.newspaperPageForSource
import me.ash.reader.ui.page.home.flow.newspaperRows
import me.ash.reader.ui.page.home.flow.applyTo
import me.ash.reader.ui.page.home.flow.newspaperScrolledReadRange
import me.ash.reader.ui.page.home.flow.EInkVolumePageKeys
import me.ash.reader.ui.page.home.flow.EInkPagerReset
import me.ash.reader.ui.page.home.flow.EInkMeasuredPageLayout
import me.ash.reader.ui.page.home.flow.turnNewspaperPage
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.ui.page.home.flow.ArticleList
import me.ash.reader.ui.page.home.flow.CONTENT_TYPE_DATE_HEADER
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.ui.page.home.feeds.FeedsBanner
import me.ash.reader.ui.page.home.flow.ArticleItem
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.ui.page.home.feeds.PublicationBlock
import me.ash.reader.ui.page.home.feeds.PublicationGrid
import me.ash.reader.ui.page.home.feeds.NewspaperFolderHeader
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Retained native component and continuous/legacy-list regression fixtures. Screen-paged
 * acceptance lives in ViewportNewspaperUiTest, which uses the current production header/body.
 * Article links are about:blank so
 * text-only fixtures never trigger external metadata fetches; photo URLs are seeded in Coil. */
class NewspaperUiTest {
    // Queue effects on the Compose test clock instead of resuming dialog disposal on DataStore IO.
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun publicationsRenderAndKeepActionsAtEveryWidth() {
        val opened = AtomicInteger()
        val options = AtomicInteger()
        val names = listOf("The Morning Journal", "Science & Nature", "Design Quarterly", "世界新闻与文化", "Independent Dispatch", "A publication with a deliberately very long name")
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = true) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                        FeedsBanner(filter = Filter.Unread, desc = Filter.Unread.toDesc(143))
                        Spacer(Modifier.height(20.dp))
                        PublicationGrid(itemCount = names.size) { index, modifier ->
                            PublicationBlock(name = names[index], count = if (index == 0) 128 else index,
                                source = "example.com", modifier = modifier.testTag("publication-$index"),
                                onClick = { opened.incrementAndGet() },
                                onLongClick = { options.incrementAndGet() })
                        }
                    }
                    }
                }
            }
        }
        compose.onNodeWithTag("publication-0").assertIsDisplayed().performClick().performClick()
        assertEquals(2, opened.get())
        compose.onNodeWithTag("publication-0").performTouchInput { longClick() }
        assertEquals(1, options.get())
        val first = compose.onNodeWithTag("publication-0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("publication-1").fetchSemanticsNode().boundsInRoot
        val width = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
        if (resolveReadingLayout(width.toFloat()).publicationColumns == 1)
            assertTrue("Narrow reading order is vertical", second.top >= first.bottom)
        else assertTrue("Wide publications form columns", second.left >= first.right)
        capture("subscriptions")
        compose.onNodeWithTag("publication-5").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(3, opened.get())
        assertLastBlockInsideSafeViewport(compose.onNodeWithTag("publication-5"))
    }

    @Test fun articlesRenderRealPagingRowsAndKeepSourceIndices() {
        val opened = mutableListOf<Int>()
        val readActions = mutableListOf<String>()
        val starActions = mutableListOf<String>()
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        val photo = cachedEarthriseUrl()
        val titles = listOf("Earthrise: a fresh view of our home", "Across the lunar horizon", "The long view: why patient observation matters in science and everyday life", "世界新闻：城市中的自然", "An independent perspective")
        val feed = Feed(id = "fixture", name = "NASA Archive / Bill Anders", url = "https://example.com", groupId = "g", accountId = 1)
        val items = listOf(ArticleFlowItem.Date("Today", false)) + titles.mapIndexed { index, title ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "a$index", date = Date(0), title = title,
                rawDescription = "", img = if (index < 2) photo else null,
                shortDescription = if (index < 2) "Historical photograph: NASA/Bill Anders. A new perspective from Apollo 8." else "Thoughtful reporting, clear ideas and a little room to think. An edition made for unhurried reading.",
                link = "about:blank", feedId = feed.id, accountId = 1,
                isUnread = index != 2, isStarred = index == 1).apply { dateString = "09:00" }, feed))
        }
        val stream = flowOf(PagingData.from(items))
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalArticleListSwipeEndAction provides SwipeEndActionPreference.ToggleRead) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false) {
                    val articles = stream.collectAsLazyPagingItems()
                    val state = rememberLazyListState()
                    SideEffect { list.set(state) }
                    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        val layout = resolveReadingLayout(maxWidth.value, LocalDensity.current.fontScale,
                            LocalReadingLayoutPreset.current.preset, LocalReadingColumns.current.columns)
                        LazyColumn(Modifier.testTag("article-list"), state = state) {
                            ArticleList(pagingItems = articles, diffMap = emptyMap(), isShowFeedIcon = false,
                                isShowStickyHeader = false, articleListTonalElevation = 0, columns = layout.columns,
                                horizontalPadding = layout.horizontalPaddingDp.dp, columnSpacing = layout.columnSpacingDp.dp,
                                isSwipeEnabled = { true },
                                onClick = { _, sourceIndex -> opened.add(sourceIndex) },
                                onToggleRead = { readActions.add(it.article.id) },
                                onToggleStarred = { starActions.add(it.article.id) })
                        }
                    }
                    }
                }
            }
        }
        compose.onNodeWithText(titles[0]).assertIsDisplayed().performClick().performClick()
        assertEquals(listOf(1, 1), opened)
        waitForPhotoPixels(articlePhotoFor(titles[0]))
        compose.onNode(articlePhotoFor(titles[0]), useUnmergedTree = true).assertIsDisplayed()
        capture("articles")
        compose.onNodeWithTag("article-list").performScrollToNode(hasText(titles[2]))
        compose.onNodeWithText(titles[2]).assertIsDisplayed().performClick()
        assertEquals(3, opened.last())
        // Source index 3 remains an independently actionable supporting story in every template.
        // Keep menu placement, callbacks, dismissal and repeat openings under regression coverage.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val readLabel = context.getString(R.string.mark_as_unread) // fixture a2 is already read
        val starLabel = context.getString(R.string.mark_as_starred)
        compose.onNodeWithText(titles[2]).performTouchInput { longClick() }
        val menuBounds = compose.onNodeWithText(readLabel).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue("Menu remains inside the screen horizontally", menuBounds.left >= 0 && menuBounds.right <= context.resources.displayMetrics.widthPixels)
        assertTrue("Menu remains inside the screen vertically", menuBounds.top >= 0 && menuBounds.bottom <= context.resources.displayMetrics.heightPixels)
        compose.onNodeWithText(readLabel).performClick()
        assertEquals(listOf("a2"), readActions)
        compose.onNodeWithText(readLabel).assertDoesNotExist()
        compose.onNodeWithText(titles[2]).performTouchInput { longClick() }
        compose.onNodeWithText(starLabel).assertIsDisplayed().performClick()
        assertEquals(listOf("a2"), starActions)
        compose.onNodeWithText(titles[2]).performTouchInput { longClick() }
        compose.onNodeWithText(starLabel).assertIsDisplayed()
        pressBackFromModal(compose.onNodeWithText(starLabel), compose.onNodeWithTag("article-list"))
        compose.onNodeWithText(starLabel).assertDoesNotExist()
        // The configured shortcut dispatches once without translating the newspaper block.
        val beforeSwipe = compose.onNodeWithText(titles[2]).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(titles[2]).performTouchInput { swipeRight() }
        assertEquals(listOf("a2", "a2"), readActions)
        assertEquals(beforeSwipe, compose.onNodeWithText(titles[2]).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("article-list").performScrollToNode(hasText(titles[4]))
        compose.onNodeWithText(titles[4]).assertIsDisplayed().performClick()
        assertEquals(5, opened.last())
        compose.runOnIdle { list.get().requestScrollToItem(list.get().layoutInfo.totalItemsCount - 1) }
        assertLastBlockInsideSafeViewport(compose.onNodeWithText(titles[4]))
        compose.runOnIdle { assertTrue("The screenshot is at the actual article-list end", !list.get().canScrollForward) }
        capture("articles-end")
    }

    @Test fun offlineImageAndLargeFontRemainReadableAndLastArticleReachable() {
        val clicked = AtomicInteger()
        // An in-memory editorial illustration, decoded by the real production image composable.
        // No network, cached URL, or external resource is involved.
        val illustration = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.rgb(96, 96, 96) }
            canvas.drawRect(0f, 160f, 640f, 320f, ink)
            ink.color = Color.BLACK
            canvas.drawRect(60f, 60f, 150f, 320f, ink)
            canvas.drawRect(220f, 100f, 330f, 320f, ink)
            canvas.drawRect(400f, 30f, 510f, 320f, ink)
        }
        compose.setContent {
            val screenDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalDensity provides Density(screenDensity, fontScale = 1.6f)) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false, articleCount = 3) {
                    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("large-font-list"),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            ArticleItem(modifier = Modifier.testTag("image-article"), feedName = "Architecture Journal",
                                title = "Finding room for nature in the city", isLead = true, isUnread = true,
                                shortDescription = "An offline illustrated report, with generous type and no moving imagery.",
                                timeString = "09:00", imgData = illustration)
                        }
                        item { ArticleItem(feedName = "Science Weekly", title = "A text-only story stays complete without a photograph",
                            shortDescription = "Every block keeps its reading order as the text grows.", isUnread = false) }
                        item { ArticleItem(feedName = "The Last Page", title = "Last article remains reachable",
                            onClick = { clicked.incrementAndGet() }) }
                    }
                    }
                }
            }
        }
        // Wait for Coil's asynchronous bitmap decode, rather than photographing a placeholder.
        compose.waitUntil(10_000) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count { it == Color.rgb(96, 96, 96) } > 500
        }
        capture("articles-image-large-font")
        compose.onNodeWithTag("large-font-list").performScrollToNode(hasText("Last article remains reachable"))
        compose.onNodeWithText("Last article remains reachable").assertIsDisplayed().performClick().performClick()
        assertEquals(2, clicked.get())
        assertLastBlockInsideSafeViewport(compose.onNodeWithText("Last article remains reachable"))
        capture("articles-large-font-end")
    }

    @Test fun legacyCountSliceHelpersResetMeasuredRowsAndRetainSource() {
        // Render real rows while invoking the same page transition and scroll-request helpers
        // retained for the legacy scrollable slice. Current screen pages are tested separately.
        val feed = Feed(id = "slice-fixture", name = "Pagination Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        val fixture = (0 until 30).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "slice-$index", date = Date(0),
                // Explicit lines keep the final supporting rows taller than the viewport even
                // on a 1000dp tablet. Otherwise a legitimate end clamp hides the tested anchor.
                title = "Article $index: a deliberately substantial headline for a measured newspaper block" +
                    "\nA measured continuation stays readable.".repeat(6),
                rawDescription = "", shortDescription = "A substantial text block makes each slice overflow this bounded viewport so a retained scroll offset cannot pass unnoticed.",
                link = "about:blank", feedId = feed.id, accountId = 1), feed))
        }
        val stream = flowOf(PagingData.from<ArticleFlowItem>(fixture))
        val page = mutableStateOf(2)
        val capacity = mutableStateOf(6)
        val marked = mutableSetOf<Int>()
        val currentList = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        var columns = 1
        fun apply(change: NewspaperPageChange) {
            change.readRange?.let { marked.addAll(it) }
            change.applyTo(currentList.get()) { page.value = it }
        }
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    val items = stream.collectAsLazyPagingItems()
                    val state = rememberLazyListState()
                    SideEffect { currentList.set(state) }
                    BoxWithConstraints(Modifier.fillMaxWidth().height(360.dp)) {
                        columns = if (maxWidth >= 600.dp) 2 else 1
                        LazyColumn(Modifier.fillMaxSize().testTag("pagination-fixture"), state = state) {
                            ArticleList(pagingItems = items, diffMap = emptyMap(), isShowFeedIcon = false,
                                isShowStickyHeader = false, articleListTonalElevation = 0,
                                einkPageStart = page.value * capacity.value,
                                einkPageEnd = (page.value + 1) * capacity.value, columns = columns)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(fixture[12].articleWithFeed.article.title).assertIsDisplayed()
        compose.onNodeWithTag("pagination-fixture").performScrollToIndex(2)
        compose.runOnIdle {
            assertTrue(currentList.get().firstVisibleItemIndex > 0)
            val rows = newspaperRows(30, columns, page.value * capacity.value,
                (page.value + 1) * capacity.value) { false }
            marked.addAll(newspaperScrolledReadRange(rows, currentList.get().firstVisibleItemIndex,
                page.value * capacity.value, (page.value + 1) * capacity.value, einkMode = true))
            assertTrue("Production e-ink scroll policy does not mark unseen slices or rows", marked.isEmpty())
            apply(requireNotNull(newspaperPageTurn(page.value, 5, capacity.value, forward = true)))
        }
        compose.onNodeWithText(fixture[18].articleWithFeed.article.title).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, currentList.get().firstVisibleItemIndex)
            assertEquals(0, currentList.get().firstVisibleItemScrollOffset)
            assertEquals((12 until 18).toSet(), marked)
            assertTrue(marked.none { it < 12 })
        }
        compose.onNodeWithTag("pagination-fixture").performScrollToIndex(2)
        compose.runOnIdle { apply(requireNotNull(newspaperPageTurn(page.value, 5, capacity.value, forward = false))) }
        compose.onNodeWithText(fixture[12].articleWithFeed.article.title).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, currentList.get().firstVisibleItemIndex)
            assertEquals(0, currentList.get().firstVisibleItemScrollOffset)
            assertEquals("Previous page never marks more articles", (12 until 18).toSet(), marked)
            // Exact observed failure: page9 at capacity3 used to clamp to page5 before
            // remapping, moving source27 back to source15 when capacity grew to5.
            capacity.value = 3
            apply(newspaperPageForSource(27, 30, 3, columns, isDate = { false }))
        }
        compose.onNodeWithText(fixture[27].articleWithFeed.article.title).assertIsDisplayed()
        compose.runOnIdle {
            val rows = newspaperRows(30, columns, page.value * capacity.value,
                (page.value + 1) * capacity.value) { false }
            val sourceAnchor = rows[currentList.get().firstVisibleItemIndex].indices.first()
            assertEquals(27, sourceAnchor)
            val change = newspaperPageForSource(sourceAnchor, 30, 5, columns, isDate = { false })
            capacity.value = 5
            apply(change)
        }
        compose.onNodeWithText(fixture[27].articleWithFeed.article.title).assertIsDisplayed()
        capture("pagination-anchor")
        compose.runOnIdle {
            val rows = newspaperRows(30, columns, page.value * capacity.value,
                (page.value + 1) * capacity.value) { false }
            assertEquals(5, page.value)
            val info = currentList.get().layoutInfo
            val targetRow = rows.indexOfFirst { 27 in it.indices }
            val measuredTail = info.visibleItemsInfo.filter { it.index >= targetRow }.sumOf { it.size }
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            assertTrue("Anchor fixture must retain trailing overflow: tail=$measuredTail, viewport=$viewport",
                measuredTail > viewport)
            assertTrue("Resized first row still contains source27: first=${currentList.get().position()}, target=$targetRow, tail=$measuredTail, viewport=$viewport",
                27 in rows[currentList.get().firstVisibleItemIndex].indices)
            assertEquals("Resize is not explicit forward reading", (12 until 18).toSet(), marked)
        }
    }

    @Test fun legacyLazyListEffectsHandleVolumeRegistrationAndFilterReplacement() {
        // Retains lifecycle coverage for the legacy LazyList effects, not the screen-page controller.
        // Events enter its real hardware-volume bus; Android OS key dispatch is not simulated.
        VolumeKeyEventBus.resetForTest()
        val feed = Feed(id = "effects", name = "Transition Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        fun fixture(prefix: String) = (0 until 30).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "$prefix-$index", date = Date(0),
                // This transition fixture requires real trailing overflow, not just a long
                // sentence that becomes short when the measured tablet columns grow wider.
                title = "$prefix article $index: a substantial headline for measured transition testing" +
                    "\nA measured continuation stays readable.".repeat(6),
                rawDescription = "", shortDescription = "Enough content to force scrolling within each bounded slice, exposing incorrect retained offsets and stale list ownership.",
                link = "about:blank", feedId = feed.id, accountId = 1), feed))
        }
        val data = mapOf("a" to fixture("a"), "b" to fixture("b"))
        val pagers = data.mapValues { flowOf(PagingData.from<ArticleFlowItem>(it.value)) }
        val selected = mutableStateOf("a")
        val volumeEnabled = mutableStateOf(true)
        val page = mutableStateOf(2)
        val capacity = mutableStateOf(6)
        val targetCapacity = mutableStateOf(6)
        val columns = mutableStateOf(1)
        val marked = mutableListOf<String>()
        val currentList = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                    AppTheme(useDarkTheme = false) {
                        val pager = pagers.getValue(selected.value)
                        val items = pager.collectAsLazyPagingItems()
                        val state = remember(pager) { LazyListState() }
                        SideEffect { currentList.set(state) }
                        EInkPagerReset(pagerIdentity = pager, enabled = true, listState = state,
                            onPageChange = { page.value = it })
                        EInkVolumePageKeys(enabled = volumeEnabled.value, listState = state, onTurn = { forward ->
                            turnNewspaperPage(currentPage = page.value,
                                pageCount = maxOf(1, (items.itemCount + capacity.value - 1) / capacity.value),
                                capacity = capacity.value, forward = forward, listState = state,
                                snapshot = items.itemSnapshotList, snapshotPager = pager, activePager = pager,
                                markReadEnabled = true,
                                article = { (it as? ArticleFlowItem.Article)?.articleWithFeed },
                                onMarkRead = { articles -> marked.addAll(articles.map { it.article.id }) },
                                onPageChange = { page.value = it })
                        })
                        BoxWithConstraints(Modifier.fillMaxWidth().height(360.dp)) {
                            EInkMeasuredPageLayout(enabled = true, targetCapacity = targetCapacity.value,
                                targetColumns = if (maxWidth >= 600.dp) 2 else 1, hasMeasuredPane = true,
                                itemCount = items.itemCount,
                                isRefreshComplete = items.loadState.refresh is androidx.paging.LoadState.NotLoading,
                                listState = state, currentPage = { page.value },
                                currentCapacity = { capacity.value }, currentColumns = { columns.value },
                                isDate = { items.peek(it) is ArticleFlowItem.Date },
                                onLayoutChange = { count, columnCount -> capacity.value = count; columns.value = columnCount },
                                onPageChange = { page.value = it })
                            LazyColumn(Modifier.fillMaxSize().testTag("effects-fixture"), state = state) {
                                ArticleList(pagingItems = items, diffMap = emptyMap(), isShowFeedIcon = false,
                                    isShowStickyHeader = false, articleListTonalElevation = 0,
                                    einkPageStart = page.value * capacity.value,
                                    einkPageEnd = (page.value + 1) * capacity.value, columns = columns.value)
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText(data.getValue("a")[12].articleWithFeed.article.title).assertIsDisplayed()
            compose.onNodeWithTag("effects-fixture").performScrollToIndex(2)
            compose.runOnIdle {
                assertTrue(currentList.get().firstVisibleItemIndex > 0)
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
            }
            compose.waitUntil(5_000) { page.value == 4 }
            compose.runOnIdle {
                assertEquals(0, currentList.get().firstVisibleItemIndex)
                assertEquals(0, currentList.get().firstVisibleItemScrollOffset)
                assertEquals((12 until 24).map { "a-$it" }, marked)
            }
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.PREV) }
            compose.waitUntil(5_000) { page.value == 3 }
            compose.runOnIdle {
                assertEquals(0, currentList.get().firstVisibleItemIndex)
                assertEquals((12 until 24).map { "a-$it" }, marked)
            }
            compose.onNodeWithTag("effects-fixture").performScrollToIndex(2)
            val (oldList, oldIndex) = compose.runOnIdle {
                val list = currentList.get()
                assertTrue("Capture the old filter only after its requested scroll settles",
                    list.firstVisibleItemIndex > 0)
                list to list.firstVisibleItemIndex
            }
            compose.runOnIdle { selected.value = "b" }
            compose.waitUntil(5_000) { page.value == 0 && currentList.get() !== oldList }
            compose.onNodeWithText(data.getValue("b")[0].articleWithFeed.article.title).assertIsDisplayed()
            compose.onNodeWithTag("effects-fixture").performScrollToIndex(2)
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }
            compose.waitUntil(5_000) { page.value == 1 }
            compose.runOnIdle {
                assertEquals(0, currentList.get().firstVisibleItemIndex)
                assertEquals(0, currentList.get().firstVisibleItemScrollOffset)
                assertEquals("Stale collector must not scroll the previous filter", oldIndex, oldList.firstVisibleItemIndex)
                assertEquals((12 until 24).map { "a-$it" } + (0 until 6).map { "b-$it" }, marked)
            }
            val beforeDisabledMarks = marked.toList()
            compose.runOnIdle { volumeEnabled.value = false }
            compose.runOnIdle {
                assertTrue("Disposed list must unregister its LOW volume listener",
                    !VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
            }
            compose.runOnIdle {
                assertEquals("Disabled listener cannot turn pages", 1, page.value)
                assertEquals(beforeDisabledMarks, marked)
                volumeEnabled.value = true
            }
            compose.waitUntil(5_000) { VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW) }
            compose.runOnIdle {
                // The reading pane has HIGH priority; its registration suppresses the list.
                VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)
                assertTrue(!VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
            }
            compose.runOnIdle {
                assertEquals("Reading-pane priority suppresses list navigation", 1, page.value)
                assertEquals(beforeDisabledMarks, marked)
                VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)
                assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
                VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
            }
            compose.waitUntil(5_000) { page.value == 2 }
            compose.runOnIdle {
                assertEquals("Re-enabled listener captures the next concrete slice once",
                    beforeDisabledMarks + (6 until 12).map { "b-$it" }, marked)
                assertEquals(0, currentList.get().firstVisibleItemIndex)
                targetCapacity.value = 3
            }
            compose.waitUntil(5_000) { capacity.value == 3 }
            compose.runOnIdle {
                newspaperPageForSource(27, 30, capacity.value, columns.value, isDate = { false })
                    .applyTo(currentList.get()) { page.value = it }
            }
            compose.onNodeWithText(data.getValue("b")[27].articleWithFeed.article.title).assertIsDisplayed()
            val beforeResizeMarks = marked.toList()
            compose.runOnIdle { targetCapacity.value = 5 }
            compose.waitUntil(5_000) { capacity.value == 5 }
            capture("effects-anchor")
            compose.runOnIdle {
                assertEquals(5, page.value)
                val rows = newspaperRows(30, columns.value, 25, 30) { false }
                val info = currentList.get().layoutInfo
                val targetRow = rows.indexOfFirst { 27 in it.indices }
                val measuredTail = info.visibleItemsInfo.filter { it.index >= targetRow }.sumOf { it.size }
                val viewport = info.viewportEndOffset - info.viewportStartOffset
                assertTrue("Effect fixture must retain trailing overflow: tail=$measuredTail, viewport=$viewport",
                    measuredTail > viewport)
                assertTrue("Real measured reflow keeps source27 first: first=${currentList.get().position()}, target=$targetRow, tail=$measuredTail, viewport=$viewport",
                    27 in rows[currentList.get().firstVisibleItemIndex].indices)
                assertEquals("Real layout effect never marks reader-return or resized rows", beforeResizeMarks, marked)
            }
            compose.onNodeWithText(data.getValue("b")[27].articleWithFeed.article.title).assertIsDisplayed()
        } finally {
            VolumeKeyEventBus.resetForTest()
        }
    }

    @Test fun photoEditionHonorsPresetsImageOffAndLargeText() {
        val photo = cachedEarthriseUrl()
        val feed = Feed(id = "photo", name = "NASA / Bill Anders", url = "https://example.com", groupId = "g", accountId = 1)
        val titles = listOf(
            "Earthrise: our home beyond the lunar horizon",
            "A new perspective on Earth",
            "The text-only dispatch remains complete",
            "Mapping the quiet landscape of the Moon",
            "What the Apollo archive reveals",
            "One planet, many independent perspectives",
            "Working beyond the familiar horizon",
            "Science rewards patient observation",
            "From the archive: a lasting view of home",
            "The final story stays reachable",
        )
        val source = titles.mapIndexed { index, title -> ArticleFlowItem.Article(ArticleWithFeed(
            Article(id = "photo-$index", title = title, date = Date(0),
                rawDescription = when (index) {
                    1 -> "<p><img src=\"$photo\" alt=\"Earthrise\"></p>"
                    4 -> "<meta property=\"og:image\" content=\"$photo\">"
                    else -> ""
                },
                shortDescription = "A historical Apollo 8 photograph accompanies this offline reading fixture.",
                img = if (index in setOf(0, 3, 6, 8)) photo else null, link = "about:blank", feedId = feed.id, accountId = 1), feed)) }
        val stream = flowOf(PagingData.from<ArticleFlowItem>(source))
        val preset = mutableStateOf<ReadingLayoutPresetPreference>(ReadingLayoutPresetPreference.Auto)
        val images = mutableStateOf<FlowArticleListImagePreference>(FlowArticleListImagePreference.ON)
        val fontScale = mutableStateOf(1f)
        val paneWidth = mutableStateOf<Float?>(null)
        val measuredColumns = AtomicInteger()
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalReadingLayoutPreset provides preset.value, LocalFlowArticleListImage provides images.value,
                LocalDensity provides Density(density, fontScale.value)) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false, articleCount = titles.size) {
                        val items = stream.collectAsLazyPagingItems()
                        val state = rememberLazyListState()
                        SideEffect { list.set(state) }
                        BoxWithConstraints(Modifier.fillMaxHeight().widthIn(max = paneWidth.value?.dp ?: androidx.compose.ui.unit.Dp.Infinity).fillMaxWidth()) {
                            val layout = resolveReadingLayout(maxWidth.value, fontScale.value, preset.value.preset)
                            SideEffect { measuredColumns.set(layout.columns) }
                            LazyColumn(Modifier.testTag("photo-edition"), state = state) {
                                ArticleList(items, emptyMap(), false, false, 0, columns = layout.columns,
                                    horizontalPadding = layout.horizontalPaddingDp.dp, columnSpacing = layout.columnSpacingDp.dp)
                            }
                        }
                    }
                }
            }
        }
        for (choice in ReadingLayoutPresetPreference.values) {
            compose.runOnIdle { preset.value = choice; list.get().requestScrollToItem(0) }
            compose.onNodeWithText(titles[0], useUnmergedTree = true).assertIsDisplayed()
            waitForPhotoPixels(articlePhotoFor(titles[0]))
            val photograph = compose.onNode(articlePhotoFor(titles[0]), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val headline = compose.onNodeWithText(titles[0], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("The lead headline precedes its photograph", headline.bottom <= photograph.top + 1f)
            compose.onNodeWithTag("article-lead-body", useUnmergedTree = true).assertIsDisplayed()
            val description = compose.onNode(hasTestTag("article-description") and
                hasAnyAncestor(hasTestTag("article-lead-body")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Lead copy and photo occupy separate side-by-side columns", description.right <= photograph.left)
            assertEquals("Lead copy begins beside the photograph", description.top, photograph.top, 1f)
            // Wait for the visible supporting-photo group too, not just the lead. The broad
            // tablet screenshot must show a decoded mosaic, not transient empty image slots.
            waitForPhotoPixels(articlePhotoFor(titles[1]))
            if (measuredColumns.get() >= 3) waitForPhotoPixels(articlePhotoFor(titles[3]))
            if (measuredColumns.get() == 4) waitForPhotoPixels(articlePhotoFor(titles[4]))
            capture("photos-${choice.preset.name.lowercase()}")
            compose.onNodeWithTag("photo-edition").performScrollToIndex(1)
            val firstSupport = compose.onNodeWithText(titles[1]).fetchSemanticsNode().boundsInRoot
            val secondSupport = compose.onNodeWithText(titles[2]).fetchSemanticsNode().boundsInRoot
            if (measuredColumns.get() >= 2) assertTrue(secondSupport.left > firstSupport.left)
            else assertTrue(secondSupport.top >= firstSupport.top)
        }
        compose.onNodeWithTag("photo-edition").performScrollToNode(hasText(titles[1]))
        val supportingHeadline = compose.onNodeWithText(titles[1], useUnmergedTree = true)
        waitForPhotoPixels(articlePhotoFor(titles[1]))
        val supportingPhoto = compose.onNode(articlePhotoFor(titles[1]), useUnmergedTree = true)
        assertTrue("Supporting headline precedes its photo", supportingHeadline.fetchSemanticsNode().boundsInRoot.top < supportingPhoto.fetchSemanticsNode().boundsInRoot.top)
        compose.runOnIdle { images.value = FlowArticleListImagePreference.OFF; list.get().requestScrollToItem(0) }
        compose.onAllNodesWithTag("article-photo", useUnmergedTree = true).assertCountEquals(0)
        capture("photos-off")
        compose.runOnIdle { fontScale.value = 1.6f }
        for (choice in ReadingLayoutPresetPreference.values) {
            compose.runOnIdle { preset.value = choice }
            compose.onNodeWithTag("photo-edition").performScrollToNode(hasText(titles.last()))
            compose.onNodeWithText(titles.last()).assertIsDisplayed()
            val width = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
            assertEquals("Large font retains columns only where the actual text budget fits",
                if (width <= 400) 1 else if (width >= 1000 && choice != ReadingLayoutPresetPreference.Phone) 3 else 2, measuredColumns.get())
        }
        compose.runOnIdle { fontScale.value = 1f; paneWidth.value = 280f; list.get().requestScrollToItem(0) }
        compose.waitUntil(5_000) { measuredColumns.get() == 1 }
        compose.onNodeWithText(titles[0]).assertIsDisplayed()
        compose.runOnIdle { paneWidth.value = 320f }
        compose.waitUntil(5_000) { measuredColumns.get() == 2 }
        compose.onNodeWithText(titles[0]).assertIsDisplayed()
        compose.runOnIdle { paneWidth.value = null }
        val width = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
        compose.waitUntil(5_000) { measuredColumns.get() == resolveReadingLayout(width.toFloat(), 1f, ReadingLayoutPreset.Tablet).columns }
        compose.onNodeWithTag("photo-edition").performScrollToNode(hasText(titles.last()))
        compose.onNodeWithText(titles.last()).assertIsDisplayed()
    }

    @Test fun cachedHttpsPhotographRendersWithNetworkAndMemoryCacheDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousLoader = Coil.imageLoader(context)
        val requests = AtomicInteger()
        val url = "https://offline-fixture.invalid/earthrise.jpg"
        val displayedImage = mutableStateOf(url)
        val cache = DiskCache.Builder().directory(File(context.cacheDir, "photo-offline-${System.nanoTime()}")).build()
        val editor = requireNotNull(cache.openEditor(url))
        earthriseFile().copyTo(editor.data.toFile(), overwrite = true)
        editor.metadata.toFile().writeBytes(byteArrayOf())
        editor.commit()
        val loader = ImageLoader.Builder(context).diskCache(cache).memoryCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED).respectCacheHeaders(false)
            .okHttpClient(okhttp3.OkHttpClient.Builder().addInterceptor {
                requests.incrementAndGet()
                throw java.io.IOException("Network is disabled for offline screenshot QA")
            }.build()).build()
        try {
            val result = runBlocking { loader.execute(ImageRequest.Builder(context).data(url).build()) }
            assertTrue("HTTPS fixture decodes from the real Coil disk cache", result is SuccessResult)
            assertEquals(DataSource.DISK, (result as SuccessResult).dataSource)
            Coil.setImageLoader(loader)
            compose.setContent {
                CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                    AppTheme(useDarkTheme = false) {
                        FixtureChrome(subscriptions = false, articleCount = 1) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                ArticleItem(title = "Earthrise, available offline", feedName = "NASA / Bill Anders",
                                    shortDescription = "A cached HTTPS photograph. Network and memory cache are disabled in this test.",
                                    imgData = displayedImage.value, isLead = true, isUnread = true)
                            }
                        }
                    }
                }
            }
            waitForPhotoPixels()
            capture("photos-cached-offline")
            assertEquals("No network request was attempted", 0, requests.get())
            compose.runOnIdle { displayedImage.value = "file://${context.cacheDir}/deliberately-missing-photo.jpg" }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("article-photo", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Earthrise, available offline").assertIsDisplayed()
        } finally {
            Coil.setImageLoader(previousLoader)
            loader.shutdown()
        }
    }

    @Test fun readingPresetChooserPersistsAndBackCancels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = runBlocking { ReadingLayoutPresetPreference.fromPreferences(context.dataStore.data.first()) }
        val observed = java.util.concurrent.atomic.AtomicReference(original)
        try {
            compose.setContent {
                val selected by remember { context.dataStore.data.map { ReadingLayoutPresetPreference.fromPreferences(it) } }
                    .collectAsState(initial = original)
                SideEffect { observed.set(selected) }
                CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON, LocalReadingLayoutPreset provides selected) {
                    AppTheme(useDarkTheme = false) { ReadingLayoutPresetSetting() }
                }
            }
            for (choice in listOf(ReadingLayoutPresetPreference.Phone, ReadingLayoutPresetPreference.Tablet, ReadingLayoutPresetPreference.Auto)) {
                compose.onNodeWithText(context.getString(R.string.layout_preset)).performClick()
                compose.onNode(hasText(choice.toDesc(context)) and isSelectable()).performScrollTo().performClick()
                compose.waitUntil(5_000) { observed.get() == choice }
                val persisted = runBlocking { ReadingLayoutPresetPreference.fromPreferences(context.dataStore.data.first()) }
                assertEquals(choice, persisted)
            }
            compose.onNodeWithText(context.getString(R.string.layout_preset)).performClick()
            compose.onNode(hasText(ReadingLayoutPresetPreference.Tablet.toDesc(context)) and isSelectable()).assertIsDisplayed()
            pressBackFromModal(
                compose.onNode(hasText(ReadingLayoutPresetPreference.Tablet.toDesc(context)) and isSelectable()),
                compose.onNodeWithText(context.getString(R.string.reading_columns)),
            )
            compose.onNode(hasText(ReadingLayoutPresetPreference.Tablet.toDesc(context)) and isSelectable()).assertDoesNotExist()
            assertEquals(ReadingLayoutPresetPreference.Auto, observed.get())
            assertEquals(ReadingLayoutPresetPreference.Auto, runBlocking {
                ReadingLayoutPresetPreference.fromPreferences(context.dataStore.data.first())
            })
        } finally {
            runBlocking {
                original.put(context, this)
                withTimeout(5_000) { context.dataStore.data.first { ReadingLayoutPresetPreference.fromPreferences(it) == original } }
            }
        }
    }

    @Test fun publicationSwipesJumpWithoutOpeningOrFlinging() {
        val opened = AtomicInteger()
        val options = AtomicInteger()
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = true) {
                        val state = rememberLazyListState()
                        SideEffect { list.set(state) }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("publication-swipe-list")
                                .newspaperSwipeScroll(state),
                            state = state,
                            userScrollEnabled = false,
                        ) {
                            item {
                                NewspaperFolderHeader("The daily subscriptions", important = 143,
                                    feedCount = 72, isExpanded = true)
                            }
                            item {
                                PublicationGrid(itemCount = 72) { index, modifier ->
                                    PublicationBlock(name = "Publication $index", count = index + 1,
                                        source = "example.com", modifier = modifier.testTag("swipe-publication-$index"),
                                        onClick = { opened.incrementAndGet() },
                                        onLongClick = { options.incrementAndGet() })
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("swipe-publication-0").performTouchInput { click() }
        compose.onNodeWithTag("swipe-publication-0").performTouchInput { longClick() }
        assertEquals(1, opened.get())
        assertEquals(1, options.get())
        compose.runOnIdle { assertTrue("The gesture fixture contains more real publications than fit even on the broad tablet", list.get().canScrollForward) }
        val before = compose.runOnIdle { list.get().position() }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("swipe-publication-0").performTouchInput { swipeUp() }
        assertImmediateScrollWithoutFling(list.get(), before)
        assertEquals("Vertical swipe must not open a subscription", 1, opened.get())
        assertEquals("Vertical swipe must not open subscription options", 1, options.get())
        compose.runOnIdle { list.get().requestScrollToItem(0) }
        compose.onNodeWithTag("swipe-publication-0").performTouchInput { click() }
        compose.onNodeWithTag("swipe-publication-0").performTouchInput { longClick() }
        assertEquals(2, opened.get())
        assertEquals(2, options.get())
        capture("subscriptions-gestures")
    }

    @Test fun folderSwipesKeepDisclosureSeparateFromOpenAndOptions() {
        val opened = AtomicInteger()
        val options = AtomicInteger()
        val disclosures = AtomicInteger()
        val expanded = mutableStateOf(true)
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = true) {
                        val state = rememberLazyListState()
                        SideEffect { list.set(state) }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("folder-swipe-list")
                                .newspaperSwipeScroll(state),
                            state = state,
                            userScrollEnabled = false,
                        ) {
                            item {
                                NewspaperFolderHeader("World & culture", important = 27, feedCount = 2,
                                    isExpanded = expanded.value,
                                    onExpanded = { disclosures.incrementAndGet(); expanded.value = !expanded.value },
                                    onClick = { opened.incrementAndGet() },
                                    onLongClick = { options.incrementAndGet() })
                                if (expanded.value) {
                                    PublicationGrid(itemCount = 2) { index, modifier ->
                                        PublicationBlock("The world dispatch $index", count = 12,
                                            modifier = modifier.testTag("folder-publication-$index"))
                                    }
                                }
                            }
                            items(20) { index ->
                                NewspaperFolderHeader("Section $index", feedCount = 0,
                                    isExpanded = false, onExpanded = { disclosures.incrementAndGet() },
                                    onClick = { opened.incrementAndGet() },
                                    onLongClick = { options.incrementAndGet() })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("folder-open:World & culture").performTouchInput { click() }
        compose.onNodeWithTag("folder-open:World & culture").performTouchInput { longClick() }
        assertEquals(1, opened.get())
        assertEquals(1, options.get())
        compose.onNodeWithTag("folder-expand:World & culture").performTouchInput { click() }
        compose.onNodeWithTag("folder-publication-0").assertDoesNotExist()
        assertEquals("Disclosure must not also open the folder", 1, opened.get())
        assertEquals(1, disclosures.get())
        compose.onNodeWithTag("folder-expand:World & culture").performTouchInput { click() }
        compose.onNodeWithTag("folder-publication-0").assertIsDisplayed()
        assertEquals(2, disclosures.get())
        capture("folders")
        val before = compose.runOnIdle { list.get().position() }
        // Start directly on the actionable folder heading, not empty list padding.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("folder-open:World & culture").performTouchInput { swipeUp() }
        assertImmediateScrollWithoutFling(list.get(), before)
        assertEquals(1, opened.get())
        assertEquals(1, options.get())
        assertEquals(2, disclosures.get())
        compose.runOnIdle { list.get().requestScrollToItem(0) }
        compose.onNodeWithTag("folder-open:World & culture").performTouchInput { click() }
        compose.onNodeWithTag("folder-open:World & culture").performTouchInput { longClick() }
        assertEquals(2, opened.get())
        assertEquals(2, options.get())
        compose.onNodeWithTag("folder-expand:World & culture").performTouchInput { click() }
        compose.onNodeWithTag("folder-publication-0").assertDoesNotExist()
        assertEquals(3, disclosures.get())
        capture("folders-collapsed")
        val collapsedPosition = compose.runOnIdle { list.get().position() }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("folder-open:World & culture").performTouchInput { swipeUp() }
        assertImmediateScrollWithoutFling(list.get(), collapsedPosition)
        assertEquals("A folder-only list swipe must not open a folder", 2, opened.get())
        assertEquals(2, options.get())
        assertEquals("A folder-only list swipe must not expand a folder", 3, disclosures.get())
        compose.runOnIdle { list.get().requestScrollToItem(20) }
        compose.onNodeWithTag("folder-open:Section 19").assertIsDisplayed()
        assertLastBlockInsideSafeViewport(compose.onNodeWithTag("folder-open:Section 19"))
        compose.onNodeWithTag("folder-expand:Section 19").performTouchInput { click() }
        assertEquals("An empty folder keeps its independent disclosure target", 4, disclosures.get())
        assertEquals(2, opened.get())
    }

    @Test fun legacyScrollableSlicesTurnOnlyAtTheirConcreteBoundary() {
        val opened = mutableListOf<Int>()
        val toggled = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val page = mutableStateOf(0)
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        val feed = Feed(id = "gestures", name = "Gesture Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        val fixture = (0 until 24).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "gesture-$index", date = Date(0),
                title = "Dispatch $index: reading the newspaper without accidental actions, including longer reports that deserve space across a wide tablet edition",
                rawDescription = "", shortDescription = "Substantial reporting keeps the current page taller than its actual available viewport, at every tested device width.",
                link = "about:blank", feedId = feed.id, accountId = 1), feed))
        }
        val stream = flowOf(PagingData.from<ArticleFlowItem>(fixture))
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalArticleListSwipeEndAction provides SwipeEndActionPreference.ToggleRead) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false, articleCount = fixture.size) {
                        val items = stream.collectAsLazyPagingItems()
                        val state = rememberLazyListState()
                        SideEffect { list.set(state) }
                        fun turn(forward: Boolean) {
                            turnNewspaperPage(currentPage = page.value, pageCount = 2, capacity = 12,
                                forward = forward, listState = state, snapshot = items.itemSnapshotList,
                                snapshotPager = stream, activePager = stream, markReadEnabled = true,
                                article = { (it as? ArticleFlowItem.Article)?.articleWithFeed },
                                onMarkRead = { articles -> marked.addAll(articles.map { it.article.id }) },
                                onPageChange = { page.value = it })
                        }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val layout = resolveReadingLayout(maxWidth.value, LocalDensity.current.fontScale,
                                LocalReadingLayoutPreset.current.preset, LocalReadingColumns.current.columns)
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("article-swipe-list")
                                    .newspaperSwipeScroll(state, onPreviousBoundary = { turn(false) },
                                        onNextBoundary = { turn(true) }),
                                state = state,
                                userScrollEnabled = false,
                            ) {
                                ArticleList(items, emptyMap(), false, false, 0,
                                    columns = layout.columns, horizontalPadding = layout.horizontalPaddingDp.dp,
                                    columnSpacing = layout.columnSpacingDp.dp,
                                    einkPageStart = page.value * 12, einkPageEnd = (page.value + 1) * 12,
                                    onClick = { _, index -> opened.add(index) },
                                    onToggleRead = { toggled.add(it.article.id) })
                            }
                        }
                    }
                }
            }
        }
        val title = fixture[0].articleWithFeed.article.title
        compose.onNodeWithText(title).assertIsDisplayed()
        val before = compose.runOnIdle { list.get().position() }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(title).performTouchInput { swipeUp() }
        assertImmediateScrollWithoutFling(list.get(), before)
        compose.runOnIdle {
            assertEquals("Within-page scrolling does not turn a page", 0, page.value)
            assertTrue("Vertical scrolling never opens an article", opened.isEmpty())
            assertTrue("Vertical scrolling never triggers the horizontal shortcut", toggled.isEmpty())
            assertTrue("Scrolling never marks unseen articles", marked.isEmpty())
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.mark_as_read)).assertDoesNotExist()
        compose.runOnIdle { list.get().requestScrollToItem(0) }
        compose.onNodeWithText(title).performTouchInput { click() }
        compose.onNodeWithText(title).performTouchInput { click() }
        assertEquals(listOf(0, 0), opened)
        compose.onNodeWithText(title).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.mark_as_read)).assertIsDisplayed().performClick()
        assertEquals(listOf("gesture-0"), toggled)
        compose.onNodeWithText(title).performTouchInput { swipeRight() }
        assertEquals("Existing horizontal shortcut still works", listOf("gesture-0", "gesture-0"), toggled)
        compose.runOnIdle { list.get().requestScrollToItem(list.get().layoutInfo.totalItemsCount - 1) }
        compose.onNodeWithText(fixture[11].articleWithFeed.article.title).assertIsDisplayed()
        assertLastBlockInsideSafeViewport(compose.onNodeWithText(fixture[11].articleWithFeed.article.title))
        compose.runOnIdle { assertTrue(!list.get().canScrollForward) }
        val bottomRange = compose.onNodeWithTag("article-swipe-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle {
            assertEquals("Accessibility range ends at the actual lower boundary",
                bottomRange.value(), bottomRange.maxValue(), 0.001f)
        }
        compose.onNodeWithTag("article-swipe-list").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue("Accessibility reports that further scrolling cannot move", !scroll(0f, 100f))
        }
        compose.runOnIdle {
            assertEquals("Rejected accessibility scroll cannot turn a page", 0, page.value)
            assertTrue(marked.isEmpty())
        }
        compose.onNodeWithTag("article-swipe-list").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { page.value == 1 }
        compose.onNodeWithText(fixture[12].articleWithFeed.article.title).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0 to 0, list.get().position())
            assertEquals("Boundary swipe marks exactly the page being left", (0 until 12).map { "gesture-$it" }, marked)
        }
        compose.onNodeWithTag("article-swipe-list").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { page.value == 0 }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0 to 0, list.get().position())
            assertEquals("Backward boundary swipe never marks another page", (0 until 12).map { "gesture-$it" }, marked)
        }
    }

    @Test fun productionScaffoldReservesMeasuredFooterAndNavigationInsets() {
        val navigationInset = mutableStateOf(24)
        val extraControls = mutableStateOf(false)
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    RYScaffold(
                        reserveBottomBarSpace = true,
                        contentWindowInsets = WindowInsets(bottom = navigationInset.value.dp),
                        topBar = { NewspaperMasthead(title = "Unread", details = "20 articles") },
                        bottomBar = {
                            // Deterministic gesture/three-button insets around the real footer.
                            // Consume the host device's inset so it is not counted a second time.
                            Column(Modifier.testTag("fixture-bottom-bar")
                                .consumeWindowInsets(WindowInsets.navigationBars)) {
                                EInkPaginationBar(currentPage = 1, totalPages = 1, totalArticles = 20,
                                    onPrev = {}, onNext = {}, canIncreaseFontSize = extraControls.value)
                                Spacer(Modifier.fillMaxWidth().height(navigationInset.value.dp)
                                    .testTag("fixture-system-navigation"))
                            }
                        },
                    ) {
                        val state = rememberLazyListState()
                        SideEffect { list.set(state) }
                        LazyColumn(Modifier.fillMaxSize().testTag("fixture-content-viewport"), state = state) {
                            items(20) { index ->
                                ArticleItem(title = "Article $index", feedName = "The last page",
                                    shortDescription = "Complete article metadata stays above the fixed controls.",
                                    modifier = Modifier.padding(horizontal = 16.dp).testTag("footer-article-$index"))
                            }
                        }
                    }
                }
            }
        }
        for (inset in listOf(24, 48)) {
            for (expanded in listOf(false, true)) {
                compose.runOnIdle {
                    navigationInset.value = inset
                    extraControls.value = expanded
                }
                // Resize the viewport before asking for its final scroll position. A request
                // measured against the old taller viewport is not a bottom-anchoring contract.
                compose.waitForIdle()
                compose.runOnIdle { list.get().requestScrollToItem(19) }
                val last = compose.onNodeWithTag("footer-article-19").assertIsDisplayed()
                capture("articles-footer-insets-$inset-${if (expanded) "expanded" else "compact"}")
                assertLastBlockInsideSafeViewport(last)
                val bar = compose.onNodeWithTag("fixture-bottom-bar").getUnclippedBoundsInRoot()
                val navigation = compose.onNodeWithTag("fixture-system-navigation").getUnclippedBoundsInRoot()
                assertEquals("Requested navigation inset is fully reserved", inset.toFloat(), (navigation.bottom - navigation.top).value, 0.5f)
                assertEquals("Footer measurement includes every controls row and the navigation inset",
                    57f + inset + if (expanded) 48f else 0f, (bar.bottom - bar.top).value, 0.5f)
                assertTrue("Article is above Android navigation too", last.getUnclippedBoundsInRoot().bottom <= navigation.top)
            }
        }
        capture("articles-footer-insets")
    }

    @Test fun columnChooserPersistsAndPhoneColumnsRespectLargeType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = runBlocking { ReadingColumnsPreference.fromPreferences(context.dataStore.data.first()) }
        val observed = java.util.concurrent.atomic.AtomicReference(original)
        val fontScale = mutableStateOf(1f)
        val columns = AtomicInteger()
        val observedFontScale = java.util.concurrent.atomic.AtomicReference(1f)
        val observedWidth = java.util.concurrent.atomic.AtomicReference(0f)
        try {
            compose.setContent {
                val selected by remember { context.dataStore.data.map { ReadingColumnsPreference.fromPreferences(it) } }
                    .collectAsState(initial = original)
                val density = LocalDensity.current.density
                SideEffect { observed.set(selected) }
                CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                    LocalReadingColumns provides selected,
                    LocalReadingLayoutPreset provides ReadingLayoutPresetPreference.Phone,
                    LocalDensity provides Density(density, fontScale.value)) {
                    AppTheme(useDarkTheme = false) {
                        val previewFontScale = LocalDensity.current.fontScale
                        Column(Modifier.width(320.dp)) {
                            ReadingLayoutPresetSetting()
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val layout = resolveReadingLayout(maxWidth.value, previewFontScale,
                                    LocalReadingLayoutPreset.current.preset, LocalReadingColumns.current.columns)
                                SideEffect {
                                    columns.set(layout.columns)
                                    observedFontScale.set(previewFontScale)
                                    observedWidth.set(maxWidth.value)
                                }
                                PublicationGrid(itemCount = 2) { index, modifier ->
                                    PublicationBlock("Phone edition $index", modifier = modifier.testTag("phone-column-$index"))
                                }
                            }
                        }
                    }
                }
            }
            for (choice in listOf(ReadingColumnsPreference.Two, ReadingColumnsPreference.One, ReadingColumnsPreference.Auto)) {
                compose.onNodeWithText(context.getString(R.string.reading_columns)).performClick()
                compose.onNode(hasText(choice.toDesc(context)) and isSelectable()).performScrollTo().performClick()
                compose.waitUntil(5_000) { observed.get() == choice }
                assertEquals(choice, runBlocking { ReadingColumnsPreference.fromPreferences(context.dataStore.data.first()) })
                assertEquals(if (choice == ReadingColumnsPreference.One) 1 else 2, columns.get())
                val first = compose.onNodeWithTag("phone-column-0").getUnclippedBoundsInRoot()
                val second = compose.onNodeWithTag("phone-column-1").getUnclippedBoundsInRoot()
                if (choice == ReadingColumnsPreference.One) assertTrue(second.top >= first.bottom)
                else assertTrue("Phone subscriptions really render side by side", second.left >= first.right)
            }
            compose.onNodeWithText(context.getString(R.string.reading_columns)).performClick()
            pressBackFromModal(
                compose.onNode(hasText(ReadingColumnsPreference.Two.toDesc(context)) and isSelectable()),
                compose.onNodeWithTag("phone-column-0"),
            )
            compose.onNode(hasText(ReadingColumnsPreference.Two.toDesc(context)) and isSelectable()).assertDoesNotExist()
            assertEquals(ReadingColumnsPreference.Auto, observed.get())
            assertEquals(ReadingColumnsPreference.Auto, runBlocking {
                ReadingColumnsPreference.fromPreferences(context.dataStore.data.first())
            })
            compose.runOnIdle { fontScale.value = 1.6f }
            compose.waitForIdle()
            assertEquals("Preview renders the requested accessibility font scale at ${observedWidth.get()}dp",
                1.6f, observedFontScale.get(), 0.001f)
            assertEquals("A ${observedWidth.get()}dp phone pane with ${observedFontScale.get()}x text uses one column",
                1, columns.get())
            val first = compose.onNodeWithTag("phone-column-0").getUnclippedBoundsInRoot()
            val second = compose.onNodeWithTag("phone-column-1").getUnclippedBoundsInRoot()
            assertTrue("Accessibility text size restores vertical reading order", second.top >= first.bottom)
        } finally {
            runBlocking {
                original.put(context, this)
                withTimeout(5_000) { context.dataStore.data.first { ReadingColumnsPreference.fromPreferences(it) == original } }
            }
        }
    }

    @Test fun legacyLazyListHeldSwipeCannotTurnTheReplacementSlice() {
        VolumeKeyEventBus.resetForTest()
        val page = mutableStateOf(0)
        val marked = mutableListOf<String>()
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        val feed = Feed(id = "held", name = "Held Gesture Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        val fixture = (0 until 6).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "held-$index", date = Date(0),
                title = "Brief $index", rawDescription = "", shortDescription = "A short dispatch.",
                link = "about:blank", feedId = feed.id, accountId = 1), feed))
        }
        val stream = flowOf(PagingData.from<ArticleFlowItem>(fixture))
        try {
            compose.setContent {
                CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                    AppTheme(useDarkTheme = false) {
                        FixtureChrome(subscriptions = false, articleCount = fixture.size) {
                            val items = stream.collectAsLazyPagingItems()
                            val state = rememberLazyListState()
                            SideEffect { list.set(state) }
                            fun turn(forward: Boolean): Boolean {
                                return turnNewspaperPage(currentPage = page.value, pageCount = 3, capacity = 2,
                                    forward = forward, listState = state, snapshot = items.itemSnapshotList,
                                    snapshotPager = stream, activePager = stream, markReadEnabled = true,
                                    article = { (it as? ArticleFlowItem.Article)?.articleWithFeed },
                                    onMarkRead = { articles -> marked.addAll(articles.map { it.article.id }) },
                                    onPageChange = { page.value = it })
                            }
                            EInkVolumePageKeys(enabled = true, listState = state, onTurn = { turn(it) })
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val layout = resolveReadingLayout(maxWidth.value)
                                val key = listOf(stream, page.value, 2, layout.columns)
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().testTag("held-swipe-list")
                                        .newspaperSwipeScroll(state, gestureKey = key,
                                            currentGestureKey = { listOf(stream, page.value, 2, layout.columns) },
                                            onPreviousBoundary = { turn(false) }, onNextBoundary = { turn(true) }),
                                    state = state, userScrollEnabled = false,
                                ) {
                                    ArticleList(items, emptyMap(), false, false, 0,
                                        columns = layout.columns, horizontalPadding = layout.horizontalPaddingDp.dp,
                                        columnSpacing = layout.columnSpacingDp.dp,
                                        einkPageStart = page.value * 2, einkPageEnd = (page.value + 1) * 2)
                                }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("Brief 0").assertIsDisplayed()
            compose.runOnIdle { assertTrue("Both short articles fit before the held gesture", !list.get().canScrollForward) }
            compose.onNodeWithTag("held-swipe-list").performTouchInput {
                down(center)
                moveTo(center.copy(y = center.y - 120f), delayMillis = 100)
            }
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }
            compose.waitUntil(5_000) { page.value == 1 }
            compose.onNodeWithText("Brief 2").assertIsDisplayed()
            compose.onNodeWithTag("held-swipe-list").performTouchInput { up() }
            compose.runOnIdle {
                assertEquals("Releasing an obsolete gesture must not turn the replacement page", 1, page.value)
                assertEquals(0 to 0, list.get().position())
                assertEquals("Only the explicit volume turn marks its original concrete page",
                    listOf("held-0", "held-1"), marked)
            }
            // Cancellation is per gesture, not a permanently disabled recognizer.
            compose.onNodeWithTag("held-swipe-list").performTouchInput { swipeUp() }
            compose.waitUntil(5_000) { page.value == 2 }
            compose.onNodeWithText("Brief 4").assertIsDisplayed()
            compose.runOnIdle { assertEquals((0 until 4).map { "held-$it" }, marked) }
        } finally {
            VolumeKeyEventBus.resetForTest()
        }
    }

    @Test fun stickyDateHeaderLeavesAnOverlappingReadableStripOnShortViewports() {
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        val feed = Feed(id = "sticky", name = "Continuing Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        val continuingTitle = "A continuing report on cities and science. ".repeat(24)
        val fixture = listOf(ArticleFlowItem.Date("Today, continued", false)) + (0 until 8).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "sticky-$index", date = Date(0),
                title = if (index == 0) continuingTitle else "Following dispatch $index",
                rawDescription = "", shortDescription = "The date header must not hide unread lines during a page-sized jump.",
                link = "about:blank", feedId = feed.id, accountId = 1), feed))
        }
        val stream = flowOf(PagingData.from<ArticleFlowItem>(fixture))
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false, articleCount = 8) {
                        val items = stream.collectAsLazyPagingItems()
                        val state = rememberLazyListState()
                        SideEffect { list.set(state) }
                        BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp)) {
                            val layout = resolveReadingLayout(maxWidth.value)
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("sticky-swipe-list")
                                    .newspaperSwipeScroll(state, topObstructionPx = {
                                        val info = state.layoutInfo
                                        info.visibleItemsInfo.filter { it.contentType == CONTENT_TYPE_DATE_HEADER }
                                            .maxOfOrNull { it.size } ?: 0
                                    }),
                                state = state, userScrollEnabled = false,
                            ) {
                                ArticleList(items, emptyMap(), false, true, 0,
                                    columns = layout.columns, horizontalPadding = layout.horizontalPaddingDp.dp,
                                    columnSpacing = layout.columnSpacingDp.dp)
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Today, continued").assertIsDisplayed()
        compose.runOnIdle { list.get().requestScrollToItem(1, 40) }
        val before = compose.runOnIdle {
            val info = list.get().layoutInfo
            val header = requireNotNull(info.visibleItemsInfo.firstOrNull { it.index == 0 })
            assertTrue("The production date header is pinned", header.offset <= info.viewportStartOffset)
            assertTrue(header.size > 0)
            assertEquals(1, list.get().firstVisibleItemIndex)
            Triple(list.get().firstVisibleItemScrollOffset,
                info.viewportEndOffset - info.viewportStartOffset, header.size)
        }
        compose.mainClock.autoAdvance = false
        val position = compose.runOnIdle { list.get().position() }
        compose.onNodeWithTag("sticky-swipe-list").performTouchInput { swipeUp() }
        assertImmediateScrollWithoutFling(list.get(), position)
        compose.onNodeWithText("Today, continued").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("The continued lead article remains the reading anchor", 1, list.get().firstVisibleItemIndex)
            val jump = list.get().firstVisibleItemScrollOffset - before.first
            val readableHeight = before.second - before.third
            assertEquals("The jump excludes the opaque pinned header", readableHeight * 0.9f, jump.toFloat(), 1f)
            assertTrue("Some readable lines overlap rather than skipping behind the header", jump < readableHeight)
        }
    }

    @Test fun editorialTemplatesKeepEverySourceActionAndReflowAnchor() {
        val feed = Feed(id = "editorial", name = "The Dispatch", url = "https://example.com",
            groupId = "g", accountId = 1)
        val source = (0 until 48).map { index ->
            if (index == 0 || index == 17 || index == 35) {
                ArticleFlowItem.Date("Edition section $index", false)
            } else ArticleFlowItem.Article(ArticleWithFeed(Article(id = "editorial-$index",
                title = "Dispatch $index: a considered view", date = Date(0), rawDescription = "",
                shortDescription = "Independent reporting keeps each story and its original reading position intact.",
                link = "about:blank", feedId = feed.id, accountId = 1), feed))
        }
        val stream = flowOf(PagingData.from(source))
        val preference = mutableStateOf<ReadingColumnsPreference>(ReadingColumnsPreference.Auto)
        val page = mutableStateOf(0)
        val capacity = mutableStateOf(24)
        val targetCapacity = mutableStateOf(24)
        val columns = mutableStateOf(1)
        val opened = mutableListOf<Int>()
        val toggled = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        var turn: (Boolean) -> Boolean = { false }
        fun title(index: Int) = (source[index] as ArticleFlowItem.Article).articleWithFeed.article.title
        fun rows() = newspaperRows(source.size, columns.value, page.value * capacity.value,
            (page.value + 1) * capacity.value) { source[it] is ArticleFlowItem.Date }
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalReadingLayoutPreset provides ReadingLayoutPresetPreference.Auto,
                LocalReadingColumns provides preference.value) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false, articleCount = 45) {
                        val items = stream.collectAsLazyPagingItems()
                        val state = rememberLazyListState()
                        val fontScale = LocalDensity.current.fontScale
                        SideEffect {
                            list.set(state)
                            turn = { forward ->
                                turnNewspaperPage(currentPage = page.value,
                                    pageCount = (items.itemCount + capacity.value - 1) / capacity.value,
                                    capacity = capacity.value, forward = forward, listState = state,
                                    snapshot = items.itemSnapshotList, snapshotPager = stream, activePager = stream,
                                    markReadEnabled = true,
                                    article = { (it as? ArticleFlowItem.Article)?.articleWithFeed },
                                    onMarkRead = { stories -> marked.addAll(stories.map { it.article.id }) },
                                    onPageChange = { page.value = it })
                            }
                        }
                        // A real, measured pane inside production chrome, short enough to expose
                        // incorrect row resets while keeping the same native text/column sizes.
                        BoxWithConstraints(Modifier.fillMaxWidth().height(420.dp)) {
                            val layout = resolveReadingLayout(maxWidth.value, fontScale,
                                LocalReadingLayoutPreset.current.preset, LocalReadingColumns.current.columns)
                            EInkMeasuredPageLayout(enabled = true, targetCapacity = targetCapacity.value,
                                targetColumns = layout.columns, hasMeasuredPane = true, itemCount = items.itemCount,
                                isRefreshComplete = items.loadState.refresh is androidx.paging.LoadState.NotLoading,
                                listState = state, currentPage = { page.value }, currentCapacity = { capacity.value },
                                currentColumns = { columns.value }, isDate = { items.peek(it) is ArticleFlowItem.Date },
                                onLayoutChange = { count, lanes -> capacity.value = count; columns.value = lanes },
                                onPageChange = { page.value = it })
                            LazyColumn(Modifier.fillMaxSize().testTag("editorial-list"), state = state) {
                                ArticleList(items, emptyMap(), false, false, 0,
                                    columns = columns.value, einkPageStart = page.value * capacity.value,
                                    einkPageEnd = (page.value + 1) * capacity.value,
                                    horizontalPadding = layout.horizontalPaddingDp.dp,
                                    columnSpacing = layout.columnSpacingDp.dp,
                                    onClick = { _, index -> opened.add(index) },
                                    onToggleRead = { toggled.add(it.article.id) })
                            }
                        }
                    }
                }
            }
        }
        val width = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
        val expectedLanes = when { width >= 1000 -> 4; width >= 800 -> 3; else -> 2 }
        compose.waitUntil(5_000) { columns.value == expectedLanes }
        compose.onNodeWithTag("newspaper-masthead").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals((0 until 24).toList(), rows().flatMap { it.indices })
            assertEquals(listOf(1, 18), rows().filter { it.isLead }.flatMap { it.indices })
            list.get().requestScrollToItem(2)
        }
        val first = compose.onNodeWithTag("newspaper-slot-2", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val next = compose.onNodeWithTag("newspaper-slot-3", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("Editorial lanes start at the same printed row", first.top.value, next.top.value, 1f)
        assertTrue("Columns never overlap", next.left >= first.right)
        if (expectedLanes >= 3) {
            val gutter = resolveReadingLayout(width.toFloat()).columnSpacingDp
            assertEquals("Feature spans two actual lanes including the internal gutter",
                (next.right - next.left).value * 2f + gutter, (first.right - first.left).value, 1f)
            val upperBrief = compose.onNodeWithText(title(3)).getUnclippedBoundsInRoot()
            val lowerBrief = compose.onNodeWithText(title(4)).getUnclippedBoundsInRoot()
            assertTrue("Briefs stack vertically without overlap", lowerBrief.top >= upperBrief.bottom)
            assertEquals("Briefs preserve the same source lane", upperBrief.left.value, lowerBrief.left.value, 1f)
            if (expectedLanes == 4) {
                val lastLane = compose.onNodeWithTag("newspaper-slot-5", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("The fourth lane is genuinely to the right of the third", lastLane.left >= next.right)
                assertEquals("All narrow lanes have equal widths", (next.right - next.left).value,
                    (lastLane.right - lastLane.left).value, 1f)
                assertTrue(compose.onNodeWithText(title(6)).getUnclippedBoundsInRoot().top >=
                    compose.onNodeWithText(title(5)).getUnclippedBoundsInRoot().bottom)
            }
        } else assertEquals("Two-column preference keeps equal lane widths",
            (first.right - first.left).value, (next.right - next.left).value, 1f)
        compose.onNodeWithText(title(2)).assertIsDisplayed().performClick()
        compose.onNodeWithText(title(3)).assertIsDisplayed().performClick()
        assertEquals(listOf(2, 3), opened)
        if (expectedLanes >= 3) {
            compose.onNodeWithText(title(4)).assertIsDisplayed().performClick()
            assertEquals(listOf(2, 3, 4), opened)
        }
        compose.onNodeWithText(title(3)).performTouchInput { longClick() }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.mark_as_read))
            .assertIsDisplayed().performClick()
        assertEquals(listOf("editorial-3"), toggled)
        capture("editorial-templates")

        // Reader return into a non-leading column maps to its containing row. Layout changes
        // must preserve that live source anchor, not reinterpret a lazy-row index as an article.
        compose.runOnIdle {
            newspaperPageForSource(7, source.size, capacity.value, columns.value,
                isDate = { source[it] is ArticleFlowItem.Date }).applyTo(list.get()) { page.value = it }
        }
        compose.onNodeWithText(title(7)).assertIsDisplayed()
        for (choice in listOf(ReadingColumnsPreference.Two, ReadingColumnsPreference.One, ReadingColumnsPreference.Auto)) {
            val anchor = compose.runOnIdle { rows()[list.get().firstVisibleItemIndex].indices.first() }
            compose.runOnIdle { preference.value = choice; targetCapacity.value = 28 }
            val targetLanes = when (choice) {
                ReadingColumnsPreference.One -> 1
                ReadingColumnsPreference.Two -> 2
                else -> expectedLanes
            }
            compose.waitUntil(5_000) { columns.value == targetLanes && capacity.value == 28 }
            compose.runOnIdle {
                assertTrue("$choice preserves source $anchor in the first visible row",
                    anchor in rows()[list.get().firstVisibleItemIndex].indices)
                assertEquals("Reflow resets only the pixel offset", 0, list.get().firstVisibleItemScrollOffset)
                assertTrue("Changing layout never marks unseen stories", marked.isEmpty())
            }
            compose.onNodeWithText(title(anchor)).assertIsDisplayed()
        }
        compose.runOnIdle {
            assertTrue(turn(true))
            assertEquals("Only concrete stories in the page being left are marked",
                (0 until 28).filter { source[it] is ArticleFlowItem.Article }.map { "editorial-$it" }, marked)
        }
        compose.onNodeWithText(title(28)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0 to 0, list.get().position())
            val before = marked.toList()
            assertTrue(turn(false))
            assertEquals("Backward navigation cannot mark the next slice", before, marked)
        }
    }

    @Test fun savedMetadataPhotoAlternateSurvivesDecodeFailureAndMissingPhotosStayReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousLoader = Coil.imageLoader(context)
        val cache = DiskCache.Builder().directory(File(context.cacheDir, "photo-alternates-${System.nanoTime()}")).build()
        val base = "https://offline-fixture.invalid/${System.nanoTime()}"
        val broken = "$base-primary.jpg"
        val alternate = "$base-alternate.jpg"
        val alsoBroken = "$base-second.jpg"
        for (url in listOf(broken, alternate, alsoBroken)) {
            val editor = requireNotNull(cache.openEditor(url))
            if (url == alternate) earthriseFile().copyTo(editor.data.toFile(), overwrite = true)
            else editor.data.toFile().writeText("This cache entry is intentionally not an image.")
            editor.metadata.toFile().writeBytes(byteArrayOf())
            editor.commit()
        }
        // Network is disabled in the inherited Coil request policy, including the production
        // article loader. Both the failed primary and successful alternate come from disk.
        val loader = ImageLoader.Builder(context).diskCache(cache)
            .memoryCachePolicy(CachePolicy.DISABLED).networkCachePolicy(CachePolicy.DISABLED)
            .respectCacheHeaders(false).build()
        val feed = Feed(id = "fallback", name = "NASA / Bill Anders", url = "https://example.com",
            groupId = "g", accountId = 1)
        fun article(primary: String?, savedImage: String?) = ArticleWithFeed(Article(id = "alternate-photo",
            title = "A photograph recovered from saved story metadata", date = Date(0),
            img = primary, rawDescription = savedImage?.let { "<meta property=\"og:image\" content=\"$it\">" }.orEmpty(),
            shortDescription = "The full headline and its source remain readable even when every photo fails.",
            link = "about:blank", feedId = feed.id, accountId = 1), feed)
        val displayed = mutableStateOf(article(broken, alternate))
        val opened = AtomicInteger()
        try {
            val failed = runBlocking { loader.execute(ImageRequest.Builder(context).data(broken).build()) }
            assertTrue("Primary fixture really fails decoding", failed is coil.request.ErrorResult)
            val recovered = runBlocking { loader.execute(ImageRequest.Builder(context).data(alternate).build()) }
            assertTrue("Alternate fixture decodes the historical photograph", recovered is SuccessResult)
            assertEquals(DataSource.DISK, (recovered as SuccessResult).dataSource)
            Coil.setImageLoader(loader)
            compose.setContent {
                CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                    AppTheme(useDarkTheme = false) {
                        FixtureChrome(subscriptions = false, articleCount = 1) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                ArticleItem(articleWithFeed = displayed.value, isLead = true,
                                    onClick = { opened.incrementAndGet() })
                            }
                        }
                    }
                }
            }
            waitForPhotoPixels()
            val title = displayed.value.article.title
            val headline = compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val photo = compose.onNodeWithTag("article-photo", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("Fallback photos never move ahead of the headline", headline.bottom <= photo.top)
            capture("photos-alternate")
            compose.runOnIdle { displayed.value = article(broken, alsoBroken) }
            compose.waitUntil(10_000) {
                // Async saved-HTML parsing starts with no image. Absence alone could pass
                // before the new request even exists, so require its actual decode failure.
                ArticleImageEnrichment.failedRecently(alsoBroken) &&
                    compose.onAllNodesWithTag("article-photo", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            assertEquals("Failed photos cannot shift the headline's reading anchor", headline,
                compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot())
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            compose.onNodeWithText(feed.name, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(displayed.value.article.shortDescription, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(1, opened.get())
            capture("photos-missing")
            compose.runOnIdle { displayed.value = article(null, null) }
            compose.onAllNodesWithTag("article-photo", useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            assertEquals("A truly text-only story retains its complete action", 2, opened.get())
            assertEquals(headline, compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot())
        } finally {
            Coil.setImageLoader(previousLoader)
            loader.shutdown()
        }
    }

    @Test fun readerReturnReachesLowerStackedBriefWithoutReadingUnseenStories() {
        val selectedTitle = "The selected lower brief"
        val feed = Feed(id = "reader-return", name = "The Dispatch", url = "https://example.com",
            groupId = "g", accountId = 1)
        val fixture = (0 until 16).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "reader-return-$index", date = Date(0),
                title = when (index) {
                    2 -> "A continuing brief retains every line of its substantial headline. ".repeat(18)
                    3 -> selectedTitle
                    else -> "Dispatch $index"
                }, rawDescription = "", shortDescription = "", link = "about:blank",
                feedId = feed.id, accountId = 1), feed))
        }
        val stream = flowOf(PagingData.from<ArticleFlowItem>(fixture))
        val restoration = NewspaperArticleRestoration()
        val generation = mutableStateOf(0)
        val columns = AtomicInteger()
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        val opened = mutableListOf<Int>()
        val marked = mutableSetOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalReadingLayoutPreset provides ReadingLayoutPresetPreference.Auto,
                LocalReadingColumns provides ReadingColumnsPreference.Auto) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false, articleCount = fixture.size) {
                        val items = stream.collectAsLazyPagingItems()
                        val state = rememberLazyListState()
                        BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp)) {
                            val layout = resolveReadingLayout(maxWidth.value)
                            SideEffect { list.set(state); columns.set(layout.columns) }
                            LazyColumn(Modifier.fillMaxSize().testTag("reader-return-viewport"), state = state) {
                                ArticleList(items, emptyMap(), false, false, 0, columns = layout.columns,
                                    horizontalPadding = layout.horizontalPaddingDp.dp,
                                    columnSpacing = layout.columnSpacingDp.dp,
                                    restoreArticleId = restoration.target?.articleId,
                                    onArticlePositioned = { id, source, row, lanes, offset ->
                                        restoration.measured(id, source, row, lanes, offset,
                                            layout.columns to generation.value, state)
                                    },
                                    onClick = { _, index -> opened.add(index) })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Dispatch 0").assertIsDisplayed()
        val rows = newspaperRows(fixture.size, columns.get()) { false }
        val selectedRow = rows.indexOfFirst { 3 in it.indices }
        compose.runOnIdle { list.get().requestScrollToItem(selectedRow) }
        if (columns.get() >= 3) {
            val upper = compose.onNodeWithText(fixture[2].articleWithFeed.article.title).getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("reader-return-viewport").getUnclippedBoundsInRoot()
            assertTrue("The upper brief is genuinely taller than the reading pane",
                upper.bottom - upper.top > viewport.bottom - viewport.top)
            compose.onNodeWithText(selectedTitle).assertIsNotDisplayed()
        }
        compose.runOnIdle {
            restoration.request(NewspaperArticleRestoreTarget(articleId = "reader-return-3", sourceIndex = 3,
                rowIndex = selectedRow, columns = columns.get(), layoutKey = columns.get() to generation.value), list.get())
        }
        compose.waitUntil(5_000) { restoration.target == null }
        val selected = compose.onNodeWithText(selectedTitle).assertIsDisplayed()
        val selectedBounds = selected.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("reader-return-viewport").getUnclippedBoundsInRoot()
        assertTrue("The complete selected brief is reachable after reader return: selected=$selectedBounds, viewport=$viewport",
            selectedBounds.top >= viewport.top - 1.dp && selectedBounds.bottom <= viewport.bottom + 1.dp)
        selected.performClick()
        assertEquals(listOf(3), opened)
        compose.runOnIdle {
            if (columns.get() >= 3) assertTrue("Restoration uses the measured intra-row offset",
                list.get().firstVisibleItemScrollOffset > (viewport.bottom - viewport.top).value *
                    InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density)
            assertTrue("Reader return is an immediate position request, not an animation", !list.get().isScrollInProgress)
            marked.addAll(newspaperScrolledReadRange(rows, list.get().firstVisibleItemIndex,
                0, fixture.size, einkMode = true))
            assertTrue("Reader return cannot mark skipped or unseen stories", marked.isEmpty())
        }
        capture("editorial-reader-return")

        // A replacement layout owns the next measure. A stale selected-story callback must
        // never apply its old intra-row offset to that layout, even for the same article id.
        compose.runOnIdle {
            restoration.request(NewspaperArticleRestoreTarget(articleId = "reader-return-3", sourceIndex = 3,
                rowIndex = selectedRow, columns = columns.get(), layoutKey = columns.get() to generation.value), list.get())
            generation.value++
        }
        compose.waitUntil(5_000) { restoration.target == null }
        compose.runOnIdle {
            assertEquals("A stale layout key cannot restore the old lower-brief offset", 0,
                list.get().firstVisibleItemScrollOffset)
            marked.addAll(newspaperScrolledReadRange(rows, list.get().firstVisibleItemIndex,
                0, fixture.size, einkMode = true))
            assertTrue(marked.isEmpty())
        }
        if (columns.get() >= 3) compose.onNodeWithText(selectedTitle).assertIsNotDisplayed()
    }

    @Test fun compactCountUsesStaticDialogAndLatestConcretePageWithoutHiddenReads() {
        val page = mutableStateOf(0)
        val marked = mutableListOf<String>()
        val opened = mutableListOf<Int>()
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        val turn = java.util.concurrent.atomic.AtomicReference<(Boolean) -> Boolean>()
        val feed = Feed(id = "count-navigation", name = "The Count Edition", url = "https://example.com",
            groupId = "g", accountId = 1)
        val fixture = (0 until 12).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "count-$index", date = Date(0),
                title = "Count edition dispatch $index", rawDescription = "", shortDescription = "A complete report with source and reading metadata.",
                link = "about:blank", feedId = feed.id, accountId = 1), feed))
        }
        val stream = flowOf(PagingData.from<ArticleFlowItem>(fixture))
        val lastSync = System.currentTimeMillis() - 120_000L
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false, articleCount = fixture.size,
                        articlePage = page.value + 1, articlePages = 3, lastSyncTime = lastSync,
                        onPreviousPage = { turn.get().invoke(false) }, onNextPage = { turn.get().invoke(true) }) {
                        val items = stream.collectAsLazyPagingItems()
                        val state = rememberLazyListState()
                        SideEffect {
                            list.set(state)
                            turn.set { forward ->
                                turnNewspaperPage(currentPage = page.value, pageCount = 3, capacity = 4,
                                    forward = forward, listState = state, snapshot = items.itemSnapshotList,
                                    snapshotPager = stream, activePager = stream, markReadEnabled = true,
                                    article = { (it as? ArticleFlowItem.Article)?.articleWithFeed },
                                    onMarkRead = { articles -> marked.addAll(articles.map { it.article.id }) },
                                    onPageChange = { page.value = it })
                            }
                        }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val layout = resolveReadingLayout(maxWidth.value)
                            LazyColumn(Modifier.fillMaxSize().testTag("count-navigation-list"), state = state) {
                                ArticleList(items, emptyMap(), false, false, 0, columns = layout.columns,
                                    horizontalPadding = layout.horizontalPaddingDp.dp,
                                    columnSpacing = layout.columnSpacingDp.dp,
                                    einkPageStart = page.value * 4, einkPageEnd = (page.value + 1) * 4,
                                    onClick = { _, index -> opened.add(index) })
                            }
                        }
                    }
                }
            }
        }
        val control = compose.onNodeWithTag("article-page-control")
        val dialog = compose.onNodeWithTag("article-page-dialog")
        val host = compose.onNodeWithTag("count-navigation-list")
        control.assertIsDisplayed().assertHasClickAction()
        dialog.assertDoesNotExist()
        compose.onNodeWithTag("fixture-bottom-bar").assertDoesNotExist()
        compose.onNodeWithText(fixture[0].articleWithFeed.article.title).assertIsDisplayed()
        val viewport = compose.onNodeWithTag("fixture-content-viewport").getUnclippedBoundsInRoot()
        val position = compose.runOnIdle { list.get().position() }
        capture("compact-count-closed", fullWindow = true)

        try {
            compose.mainClock.autoAdvance = false
            control.performClick()
            compose.mainClock.advanceTimeByFrame()
            dialog.assertIsDisplayed()
            assertStaticDialog(dialog)
            compose.onNodeWithTag("article-page-last-sync").assertIsDisplayed()
            compose.onNodeWithTag("article-page-previous").assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithTag("article-page-next").assertIsEnabled()
            capture("compact-count-open", fullWindow = true)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        pressBackFromModal(dialog, host)
        dialog.assertDoesNotExist()
        control.performClick()
        compose.onNodeWithTag("article-page-close").assertIsDisplayed().performClick()
        dialog.assertDoesNotExist()
        control.performClick()
        dismissDialogFromOutside(dialog, host)
        dialog.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("Count/Back/Close/outside taps cannot change the current page", 0, page.value)
            assertEquals("Dismissing controls preserves the reading position", position, list.get().position())
            assertTrue("Opening and dismissing controls never marks an unseen article", marked.isEmpty())
            assertTrue("A dismissing tap does not click through into an article", opened.isEmpty())
        }
        assertEquals("Opening controls does not resize or permanently cover the reading pane", viewport,
            compose.onNodeWithTag("fixture-content-viewport").getUnclippedBoundsInRoot())

        control.performClick()
        compose.onNodeWithTag("article-page-next").performClick()
        dialog.assertDoesNotExist()
        compose.onNodeWithText(fixture[4].articleWithFeed.article.title).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, page.value)
            assertEquals(0 to 0, list.get().position())
            assertEquals("Next marks only the concrete page being left", (0 until 4).map { "count-$it" }, marked)
        }
        control.performClick()
        assertPageStatus(2, 3)
        compose.onNodeWithTag("article-page-previous").assertIsEnabled()
        compose.onNodeWithTag("article-page-next").assertIsEnabled()
        // A hardware/page-controller change may happen while the controls are open. Neither
        // the status nor the next callback may retain the page captured when they opened.
        compose.runOnIdle { assertTrue(turn.get().invoke(true)) }
        assertPageStatus(3, 3)
        compose.onNodeWithTag("article-page-next").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("article-page-previous").performClick()
        dialog.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("Previous uses the latest page, not its opening page", 1, page.value)
            assertEquals("Previous and disabled Next never mark another page", (0 until 8).map { "count-$it" }, marked)
            assertTrue("Page navigation remains immediate without a scrolling animation", !list.get().isScrollInProgress)
        }
        control.performClick()
        assertPageStatus(2, 3)
        compose.onNodeWithTag("article-page-close").performClick()
        compose.onNodeWithText(fixture[4].articleWithFeed.article.title).performClick()
        assertEquals("The original activity remains actionable after repeated modal openings", listOf(4), opened)
    }

    @Test fun compactCountHandlesEmptySinglePageAndReplacedCounts() {
        val count = mutableStateOf(0)
        val pages = mutableStateOf(1)
        val current = mutableStateOf(1)
        val previous = AtomicInteger()
        val next = AtomicInteger()
        val navigationKey = mutableStateOf("first-feed")
        val callbackVersion = mutableStateOf(1)
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    // Capture an immutable value per composition so this detects a stale
                    // callback retained by an already-open production control.
                    val version = callbackVersion.value
                    FixtureChrome(subscriptions = false, articleCount = count.value,
                        articlePage = current.value, articlePages = pages.value, navigationKey = navigationKey.value,
                        onPreviousPage = { previous.addAndGet(version) }, onNextPage = { next.addAndGet(version) }) {
                        Text("The current edition stays visible", Modifier.testTag("empty-count-host"))
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val control = compose.onNodeWithTag("article-page-control")
        for (articleCount in listOf(0, 1)) {
            compose.runOnIdle { count.value = articleCount }
            control.performClick()
            compose.onNodeWithTag("article-page-previous").assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithTag("article-page-next").assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithTag("article-page-last-sync").assertDoesNotExist()
            compose.onNodeWithTag("article-page-close").performClick()
        }
        compose.runOnIdle { count.value = 45; pages.value = 9; current.value = 8 }
        control.performClick()
        assertPageStatus(8, 9)
        compose.runOnIdle { count.value = 2; pages.value = 1; current.value = 1 }
        assertPageStatus(1, 1)
        compose.onNodeWithTag("article-page-previous").assertIsNotEnabled()
        compose.onNodeWithTag("article-page-next").assertIsNotEnabled()
        compose.onNodeWithTag("article-page-close").performClick()
        control.assert(hasContentDescription(context.getString(R.string.articles_count, 2), substring = true))
        control.performClick()
        assertPageStatus(1, 1)
        pressBackFromModal(compose.onNodeWithTag("article-page-dialog"), compose.onNodeWithTag("empty-count-host"))
        control.performClick()
        compose.runOnIdle { navigationKey.value = "replacement-feed"; count.value = 0 }
        compose.onNodeWithTag("article-page-dialog").assertDoesNotExist()
        waitForHostFocus(compose.onNodeWithTag("empty-count-host"))
        control.performClick()
        assertPageStatus(1, 1)
        compose.onNodeWithTag("article-page-previous").assertIsNotEnabled()
        compose.onNodeWithTag("article-page-next").assertIsNotEnabled()
        compose.onNodeWithTag("article-page-close").performClick()
        assertEquals(0, previous.get())
        assertEquals(0, next.get())
        compose.runOnIdle { count.value = 12; pages.value = 3; current.value = 2 }
        control.performClick()
        compose.runOnIdle { callbackVersion.value = 2 }
        compose.onNodeWithTag("article-page-next").performClick()
        compose.onNodeWithTag("article-page-dialog").assertDoesNotExist()
        assertEquals("Next invokes the latest callback exactly once, not the opening callback", 2, next.get())
        control.performClick()
        compose.runOnIdle { callbackVersion.value = 3 }
        compose.onNodeWithTag("article-page-previous").performClick()
        compose.onNodeWithTag("article-page-dialog").assertDoesNotExist()
        assertEquals("Previous invokes the latest callback exactly once, not the opening callback", 3, previous.get())
    }

    @Test fun compactHeaderKeepsCountTouchTargetAndTitleSeparateAtLargeType() {
        val scale = mutableStateOf(1f)
        val title = "A publication with a deliberately very long name"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalDensity provides Density(density, scale.value)) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false, articleTitle = title, articleCount = 99999,
                        articlePage = 999, articlePages = 1000) {
                        ArticleItem(title = "The first article keeps its own space", feedName = "The Dispatch",
                            shortDescription = "Neither the count nor its touch target covers this content.",
                            modifier = Modifier.padding(horizontal = 16.dp).testTag("compact-header-first-article"))
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (fontScale in listOf(1f, 1.6f, 2f)) {
            compose.runOnIdle { scale.value = fontScale }
            val control = compose.onNodeWithTag("article-page-control").assertIsDisplayed().assertHasClickAction()
            val target = control.getUnclippedBoundsInRoot()
            val masthead = compose.onNodeWithTag("newspaper-masthead").getUnclippedBoundsInRoot()
            val titleBounds = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
            val article = compose.onNodeWithTag("compact-header-first-article").getUnclippedBoundsInRoot()
            assertTrue("Count keeps a real 48dp touch target at fontScale=$fontScale: $target",
                target.right - target.left >= 48.dp && target.bottom - target.top >= 48.dp)
            assertTrue("Count stays inside the actual masthead width at fontScale=$fontScale: $target in $masthead",
                target.left >= masthead.left && target.right <= masthead.right + 1.dp)
            assertTrue("Count never overlaps the edition title at fontScale=$fontScale: title=$titleBounds, count=$target",
                titleBounds.right <= target.left + 1.dp && titleBounds.right > titleBounds.left)
            assertTrue("Header never overlaps article content at fontScale=$fontScale", article.top >= masthead.bottom - 1.dp)
            control.assert(hasContentDescription(context.getString(R.string.articles_count, 99999), substring = true))
            control.performClick()
            assertPageStatus(999, 1000)
            compose.onNodeWithTag("article-page-previous").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("article-page-next").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("article-page-close").assertIsDisplayed().performClick()
        }
        capture("compact-count-large-font", fullWindow = true)
    }

    @Test fun footerlessArticlesReclaimTheWholeFooterAndKeepFinalRowsAboveSystemInsets() {
        val navigationInset = mutableStateOf(0)
        val showLegacyFooter = mutableStateOf(false)
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    RYScaffold(modifier = Modifier.fillMaxSize().testTag("fixture-scaffold"),
                        reserveBottomBarSpace = true,
                        contentWindowInsets = WindowInsets(bottom = navigationInset.value.dp),
                        topBar = {
                            NewspaperMasthead(title = "Unread", detailsContent = {
                                EInkArticlePageControl(currentPage = 1, totalPages = 1, totalArticles = 20,
                                    onPrev = {}, onNext = {})
                            })
                        },
                        bottomBar = {
                            if (showLegacyFooter.value) {
                                Column(Modifier.testTag("fixture-bottom-bar")
                                    .consumeWindowInsets(WindowInsets.navigationBars)) {
                                    EInkPaginationBar(currentPage = 1, totalPages = 1, totalArticles = 20,
                                        onPrev = {}, onNext = {})
                                    Spacer(Modifier.fillMaxWidth().height(navigationInset.value.dp))
                                }
                            }
                        }) {
                        val state = rememberLazyListState()
                        SideEffect { list.set(state) }
                        LazyColumn(Modifier.fillMaxSize().testTag("fixture-content-viewport"), state = state) {
                            items(20) { index ->
                                ArticleItem(title = "Complete final-page report $index", feedName = "The Dispatch",
                                    shortDescription = "The whole actionable article, including its last metadata line, must remain reachable.",
                                    modifier = Modifier.padding(horizontal = 16.dp).testTag("safe-article-$index"))
                            }
                        }
                    }
                }
            }
        }
        for (inset in listOf(0, 24, 48)) {
            compose.runOnIdle { navigationInset.value = inset; showLegacyFooter.value = false }
            compose.waitForIdle()
            compose.runOnIdle { list.get().requestScrollToItem(19) }
            compose.onNodeWithTag("fixture-bottom-bar").assertDoesNotExist()
            assertLastBlockInsideSafeViewport(compose.onNodeWithTag("safe-article-19"))
            val viewport = compose.onNodeWithTag("fixture-content-viewport").getUnclippedBoundsInRoot()
            val scaffold = compose.onNodeWithTag("fixture-scaffold").getUnclippedBoundsInRoot()
            assertEquals("Only the requested Android inset is reserved, never a phantom footer",
                inset.toFloat(), (scaffold.bottom - viewport.bottom).value, 0.5f)
            compose.runOnIdle { assertTrue("The complete last row is visible at the real list end", !list.get().canScrollForward) }
            compose.runOnIdle { showLegacyFooter.value = true }
            val legacyViewport = compose.onNodeWithTag("fixture-content-viewport").getUnclippedBoundsInRoot()
            assertEquals("Removing pagination reclaims the full 57dp legacy footer at every system inset",
                57f, (viewport.bottom - legacyViewport.bottom).value, 0.5f)
            assertEquals("The same compact header owns both comparisons", viewport.top, legacyViewport.top)
            compose.runOnIdle { showLegacyFooter.value = false }
            compose.waitForIdle()
            compose.runOnIdle { list.get().requestScrollToItem(19) }
            assertLastBlockInsideSafeViewport(compose.onNodeWithTag("safe-article-19"))
        }
        capture("compact-footerless-end", fullWindow = true)
    }

    private fun assertPageStatus(current: Int, total: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("article-page-status")
            .assertTextEquals(context.getString(R.string.article_page_position, current, total))
    }

    private fun waitForModalFocus(modal: SemanticsNodeInteraction): ViewRootForTest {
        val root = modal.assertIsDisplayed().fetchSemanticsNode().root as ViewRootForTest
        compose.waitUntil(5_000) {
            compose.runOnUiThread { root.view.isAttachedToWindow && root.view.hasWindowFocus() }
        }
        return root
    }

    private fun waitForHostFocus(host: SemanticsNodeInteraction) {
        val root = host.fetchSemanticsNode().root as ViewRootForTest
        compose.waitUntil(5_000) {
            compose.runOnUiThread { root.view.isAttachedToWindow && root.view.hasWindowFocus() }
        }
        compose.runOnIdle { assertTrue("Closing controls keeps the host resumed", root.isLifecycleInResumedState) }
        host.assertIsDisplayed()
    }

    private fun assertStaticDialog(dialog: SemanticsNodeInteraction) {
        val root = waitForModalFocus(dialog)
        compose.runOnUiThread {
            var parent = root.view.parent
            while (parent != null && parent !is DialogWindowProvider) parent = parent.parent
            val window = requireNotNull(parent as? DialogWindowProvider).window
            assertEquals("E-ink controls explicitly disable Android window transitions", 0, window.attributes.windowAnimations)
        }
        val bounds = dialog.getUnclippedBoundsInRoot()
        fun pixels(): IntArray {
            val bitmap = dialog.captureToImage().asAndroidBitmap()
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        }
        val initial = pixels()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("Dialog does not slide or expand after appearing", bounds, dialog.getUnclippedBoundsInRoot())
        assertArrayEquals("Static controls do not fade, ripple or animate after appearing", initial, pixels())
    }

    private fun dismissDialogFromOutside(modal: SemanticsNodeInteraction, host: SemanticsNodeInteraction) {
        val modalRoot = waitForModalFocus(modal)
        val hostRoot = host.fetchSemanticsNode().root as ViewRootForTest
        val modalBounds = modal.fetchSemanticsNode().boundsInRoot
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val modalOrigin = IntArray(2)
        val hostOrigin = IntArray(2)
        compose.runOnUiThread {
            modalRoot.view.getLocationOnScreen(modalOrigin)
            hostRoot.view.getLocationOnScreen(hostOrigin)
        }
        val x = hostOrigin[0] + hostBounds.left + 2f
        val y = hostOrigin[1] + hostBounds.top + 2f
        assertTrue("The native dismissal tap is genuinely outside the dialog",
            x < modalOrigin[0] + modalBounds.left || x > modalOrigin[0] + modalBounds.right ||
                y < modalOrigin[1] + modalBounds.top || y > modalOrigin[1] + modalBounds.bottom)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, now + if (action == android.view.MotionEvent.ACTION_UP) 16 else 0,
                action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        compose.waitUntil(5_000) { compose.runOnUiThread { !modalRoot.view.isAttachedToWindow } }
        waitForHostFocus(host)
    }

    private fun pressBackFromModal(modal: SemanticsNodeInteraction, host: SemanticsNodeInteraction) {
        val modalRoot = modal.assertIsDisplayed().fetchSemanticsNode().root as ViewRootForTest
        val hostRoot = host.fetchSemanticsNode().root as ViewRootForTest
        assertTrue("Back must target a separate modal window", modalRoot !== hostRoot)
        // Being composed/displayed does not mean Android has assigned this new window focus.
        // Sending Back before that transition can finish the host activity instead of cancelling
        // the dialog, falsely satisfying an assertDoesNotExist while leaving a dead composition.
        compose.waitUntil(5_000) {
            compose.runOnUiThread { modalRoot.view.isAttachedToWindow && modalRoot.view.hasWindowFocus() }
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) {
            compose.runOnUiThread { !modalRoot.view.isAttachedToWindow }
        }
        compose.runOnIdle {
            assertTrue("Cancelling a dialog/popup must leave the original activity resumed",
                hostRoot.isLifecycleInResumedState && hostRoot.view.isAttachedToWindow)
        }
        waitForHostFocus(host)
    }

    private fun LazyListState.position(): Pair<Int, Int> = firstVisibleItemIndex to firstVisibleItemScrollOffset

    private fun assertImmediateScrollWithoutFling(state: LazyListState, before: Pair<Int, Int>) {
        try {
            // One frame is enough for an immediate jump. Freezing auto-advance prevents the
            // test harness from silently waiting for an unwanted animation/fling to finish.
            compose.mainClock.advanceTimeByFrame()
            val after = compose.runOnIdle {
                assertTrue("A deliberate vertical swipe must move the list", state.position() != before)
                assertTrue("An e-ink jump is settled immediately, without a fling", !state.isScrollInProgress)
                state.position()
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.runOnIdle { assertEquals("List never drifts after finger release", after, state.position()) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun cachedEarthriseUrl(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://offline-fixture.invalid/earthrise-${System.nanoTime()}.jpg"
        val cache = requireNotNull(Coil.imageLoader(context).diskCache)
        val editor = requireNotNull(cache.openEditor(url))
        earthriseFile().copyTo(editor.data.toFile(), overwrite = true)
        editor.metadata.toFile().writeBytes(byteArrayOf())
        editor.commit()
        val decoded = runBlocking {
            Coil.imageLoader(context).execute(ImageRequest.Builder(context).data(url)
                .memoryCachePolicy(CachePolicy.DISABLED).networkCachePolicy(CachePolicy.DISABLED).build())
        }
        assertTrue("Manually seeded production cache must decode the real photo", decoded is SuccessResult)
        assertEquals(DataSource.DISK, (decoded as SuccessResult).dataSource)
        return url
    }

    private fun earthriseFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return File(instrumentation.targetContext.cacheDir, "nasa-earthrise.jpg").apply {
            instrumentation.context.assets.open("nasa-earthrise.jpg").use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    private fun articlePhotoFor(title: String): SemanticsMatcher = hasTestTag("article-photo") and
        hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(title)))

    private fun waitForPhotoPixels(matcher: SemanticsMatcher = hasTestTag("article-photo")) {
        // Inspect only the production image node, not page text. The original photograph
        // contains both broad black sky and blue Earth pixels, unlike an empty placeholder.
        compose.waitUntil(10_000) {
            val photos = compose.onAllNodes(matcher, useUnmergedTree = true)
            if (photos.fetchSemanticsNodes().isEmpty()) return@waitUntil false
            val image = photos.onFirst().captureToImage().asAndroidBitmap()
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val sky = pixels.count { Color.red(it) < 30 && Color.green(it) < 30 && Color.blue(it) < 30 }
            val earth = pixels.count { Color.blue(it) > Color.red(it) + 8 && Color.blue(it) > Color.green(it) + 2 }
            sky > pixels.size / 10 && earth > 20
        }
    }

    /** Production chrome around deterministic content fixtures, not an account-backed app session.
     * Article count/navigation is the exact production control. The unchanged app toolbar is
     * deliberately simplified here; these fixtures do not claim live-account toolbar coverage. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FixtureChrome(
        subscriptions: Boolean,
        articleCount: Int = 5,
        articlePage: Int = 1,
        articlePages: Int = 1,
        articleTitle: String? = null,
        lastSyncTime: Long? = null,
        navigationKey: Any? = null,
        onPreviousPage: () -> Unit = {},
        onNextPage: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        var filter by remember { mutableStateOf(Filter.Unread) }
        RYScaffold(
            modifier = Modifier.testTag("fixture-scaffold"),
            reserveBottomBarSpace = true,
            topBar = {
                Column {
                    TopAppBar(title = { if (!subscriptions) Text(stringResource(R.string.inkread_brand)) },
                        navigationIcon = {
                            FeedbackIconButton(
                                imageVector = if (subscriptions) Icons.Outlined.Settings else Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(if (subscriptions) R.string.settings else R.string.back))
                        },
                        actions = {
                            FeedbackIconButton(
                                imageVector = if (subscriptions) Icons.Rounded.Add else Icons.Rounded.Search,
                                contentDescription = stringResource(if (subscriptions) R.string.subscribe else R.string.search))
                        })
                    if (subscriptions) {
                        NewspaperMasthead(title = "Subscriptions")
                    } else {
                        NewspaperMasthead(title = articleTitle ?: filter.toName(), detailsContent = {
                            EInkArticlePageControl(currentPage = articlePage, totalPages = articlePages,
                                totalArticles = articleCount, lastSyncTime = lastSyncTime, navigationKey = navigationKey,
                                onPrev = onPreviousPage, onNext = onNextPage)
                        })
                    }
                }
            },
            bottomBar = {
                if (subscriptions) {
                    FilterBar(modifier = Modifier.testTag("fixture-bottom-bar"), filter = filter,
                        filterBarStyle = FlowFilterBarStylePreference.Icon.value,
                        filterBarFilled = true, filterBarPadding = 12.dp, filterBarTonalElevation = 0.dp,
                        filterOnClick = { filter = it })
                }
                // Like FlowPage, an article list has no footer placeable. RYScaffold reserves
                // only the actual Android safe inset, not a phantom legacy pagination bar.
            },
        ) {
            Box(Modifier.fillMaxSize().testTag("fixture-content-viewport")) { content() }
        }
    }

    private fun assertLastBlockInsideSafeViewport(node: SemanticsNodeInteraction) {
        // Match the complete actionable block, not just a headline that could hide clipped metadata.
        val last = node.assertHasClickAction().getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("fixture-content-viewport").getUnclippedBoundsInRoot()
        assertTrue("Complete last article/publication remains in its safe content viewport: last=$last, viewport=$viewport",
            last.top >= viewport.top - 1.dp && last.bottom <= viewport.bottom + 1.dp)
        val bars = compose.onAllNodesWithTag("fixture-bottom-bar").fetchSemanticsNodes()
        if (bars.isNotEmpty()) {
            val bar = compose.onNodeWithTag("fixture-bottom-bar").getUnclippedBoundsInRoot()
            assertTrue("Production scaffold reserves the measured bottom bar: viewport=$viewport, bar=$bar",
                viewport.bottom <= bar.top + 1.dp)
            assertTrue("Last content remains above bottom navigation: last=$last, bar=$bar", last.bottom <= bar.top + 1.dp)
        }
    }

    private fun capture(name: String, fullWindow: Boolean = false) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // AGP collects this directory before uninstalling the test/application packages.
        // A post-Gradle adb pull of externalFilesDir is too late when UTP cleans up the app.
        val additionalOutput = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = if (additionalOutput.isNullOrBlank()) {
            File(context.getExternalFilesDir(null), "newspaper-screenshots")
        } else File(additionalOutput)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create screenshot directory: $directory" }
        val output = File(directory, "newspaper-$name-${context.resources.configuration.screenWidthDp}.png")
        val bitmap = if (fullWindow) {
            // A native screenshot includes both the host activity and the real Dialog window.
            // onRoot() alone would omit the dialog or become ambiguous with multiple roots.
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        } else compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                "Could not encode screenshot: $output"
            }
        }
        check(output.length() > 0) { "Empty screenshot: $output" }
    }
}
