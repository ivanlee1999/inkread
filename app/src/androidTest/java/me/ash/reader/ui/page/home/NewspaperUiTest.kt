package me.ash.reader.ui.page.home

import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.decode.DataSource
import kotlinx.coroutines.runBlocking
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
import me.ash.reader.ui.ext.dataStore
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.espresso.Espresso.pressBack
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
import me.ash.reader.ui.component.base.newspaperSwipeScroll
import me.ash.reader.ui.page.home.flow.EInkPaginationBar
import me.ash.reader.infrastructure.preference.FlowFilterBarStylePreference
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import me.ash.reader.ui.page.home.flow.NewspaperPageChange
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native Compose fixtures, using the same production components as the subscriptions screen. */
class NewspaperUiTest {
    @get:Rule val compose = createComposeRule()

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
                        Text("Subscriptions", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(16.dp))
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
        assertAboveBottomBar(compose.onNodeWithTag("publication-5"))
    }

    @Test fun articlesRenderRealPagingRowsAndKeepSourceIndices() {
        val opened = mutableListOf<Int>()
        val readActions = mutableListOf<String>()
        val starActions = mutableListOf<String>()
        val photo = cachedEarthriseUrl()
        val titles = listOf("Earthrise: a fresh view of our home", "Across the lunar horizon", "The long view: why patient observation matters in science and everyday life", "世界新闻：城市中的自然", "An independent perspective")
        val feed = Feed(id = "fixture", name = "NASA Archive / Bill Anders", url = "https://example.com", groupId = "g", accountId = 1)
        val items = listOf(ArticleFlowItem.Date("Today", false)) + titles.mapIndexed { index, title ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "a$index", date = Date(0), title = title,
                rawDescription = "", img = if (index < 2) photo else null,
                shortDescription = if (index < 2) "Historical photograph: NASA/Bill Anders. A new perspective from Apollo 8." else "Thoughtful reporting, clear ideas and a little room to think. An edition made for unhurried reading.",
                link = "https://example.com/$index", feedId = feed.id, accountId = 1,
                isUnread = index != 2, isStarred = index == 1).apply { dateString = "09:00" }, feed))
        }
        val stream = flowOf(PagingData.from(items))
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalArticleListSwipeEndAction provides SwipeEndActionPreference.ToggleRead) {
                AppTheme(useDarkTheme = false) {
                    FixtureChrome(subscriptions = false) {
                    val articles = stream.collectAsLazyPagingItems()
                    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        val layout = resolveReadingLayout(maxWidth.value, LocalDensity.current.fontScale,
                            LocalReadingLayoutPreset.current.preset, LocalReadingColumns.current.columns)
                        LazyColumn(Modifier.testTag("article-list")) {
                            item { Text("The daily edition", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineLarge) }
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
        compose.onAllNodesWithTag("article-photo", useUnmergedTree = true).onFirst().assertIsDisplayed()
        waitForPhotoPixels()
        capture("articles")
        compose.onNodeWithTag("article-list").performScrollToNode(hasText(titles[2]))
        compose.onNodeWithText(titles[2]).assertIsDisplayed().performClick()
        assertEquals(3, opened.last())
        // Source index 3 is the right supporting column on wide layouts.
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
        pressBack()
        compose.onNodeWithText(starLabel).assertDoesNotExist()
        // The configured shortcut dispatches once without translating the newspaper block.
        val beforeSwipe = compose.onNodeWithText(titles[2]).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(titles[2]).performTouchInput { swipeRight() }
        assertEquals(listOf("a2", "a2"), readActions)
        assertEquals(beforeSwipe, compose.onNodeWithText(titles[2]).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("article-list").performScrollToNode(hasText(titles[4]))
        compose.onNodeWithText(titles[4]).assertIsDisplayed().performClick()
        assertEquals(5, opened.last())
        assertAboveBottomBar(compose.onNodeWithText(titles[4]))
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
        assertAboveBottomBar(compose.onNodeWithText("Last article remains reachable"))
        capture("articles-large-font-end")
    }

    @Test fun pageTurnsResetMeasuredRowsAndResizeKeepsExactSource() {
        // Render real rows while invoking the same page transition and scroll-request helpers
        // used by FlowPage's volume and on-screen controls. This is not a hardware-key test.
        val feed = Feed(id = "slice-fixture", name = "Pagination Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        val fixture = (0 until 30).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "slice-$index", date = Date(0),
                title = "Article $index: a deliberately substantial headline for a measured newspaper block",
                rawDescription = "", shortDescription = "A substantial text block makes each slice overflow this bounded viewport so a retained scroll offset cannot pass unnoticed.",
                link = "https://example.com/$index", feedId = feed.id, accountId = 1), feed))
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
        compose.runOnIdle {
            val rows = newspaperRows(30, columns, page.value * capacity.value,
                (page.value + 1) * capacity.value) { false }
            assertEquals(5, page.value)
            assertTrue("Resized first row still contains source27",
                27 in rows[currentList.get().firstVisibleItemIndex].indices)
            assertEquals("Resize is not explicit forward reading", (12 until 18).toSet(), marked)
        }
    }

    @Test fun productionEffectsHandleRapidVolumeBusEventsFilterReplacementAndResize() {
        // Exercises FlowPage's exact production effects/controller, not copied lifecycle logic.
        // Events enter its real hardware-volume bus; Android OS key dispatch is not simulated.
        VolumeKeyEventBus.resetForTest()
        val feed = Feed(id = "effects", name = "Transition Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        fun fixture(prefix: String) = (0 until 30).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "$prefix-$index", date = Date(0),
                title = "$prefix article $index: a substantial headline for measured transition testing",
                rawDescription = "", shortDescription = "Enough content to force scrolling within each bounded slice, exposing incorrect retained offsets and stale list ownership.",
                link = "https://example.com/$prefix/$index", feedId = feed.id, accountId = 1), feed))
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
            val oldList = currentList.get()
            val oldIndex = oldList.firstVisibleItemIndex
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
            compose.runOnIdle {
                assertEquals(5, page.value)
                val rows = newspaperRows(30, columns.value, 25, 30) { false }
                assertTrue(27 in rows[currentList.get().firstVisibleItemIndex].indices)
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
        val titles = listOf("Earthrise: our home beyond the lunar horizon", "A new perspective on Earth", "The text-only dispatch remains complete", "The final story stays reachable")
        val source = titles.mapIndexed { index, title -> ArticleFlowItem.Article(ArticleWithFeed(
            Article(id = "photo-$index", title = title, date = Date(0),
                rawDescription = if (index == 1) "<p><img src=\"$photo\" alt=\"Earthrise\"></p>" else "",
                shortDescription = "A historical Apollo 8 photograph accompanies this offline reading fixture.",
                img = if (index == 0) photo else null, link = "https://example.com/$index", feedId = feed.id, accountId = 1), feed)) }
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
                    FixtureChrome(subscriptions = false, articleCount = 4) {
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
            waitForPhotoPixels()
            val photograph = compose.onAllNodesWithTag("article-photo", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
            val headline = compose.onNodeWithText(titles[0], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Lead photography precedes the headline", photograph.bottom <= headline.top + 1f)
            capture("photos-${choice.preset.name.lowercase()}")
            compose.onNodeWithTag("photo-edition").performScrollToIndex(1)
            val firstSupport = compose.onNodeWithText(titles[1]).fetchSemanticsNode().boundsInRoot
            val secondSupport = compose.onNodeWithText(titles[2]).fetchSemanticsNode().boundsInRoot
            if (measuredColumns.get() == 2) assertTrue(secondSupport.left > firstSupport.left)
            else assertTrue(secondSupport.top >= firstSupport.top)
        }
        compose.onNodeWithTag("photo-edition").performScrollToNode(hasText(titles[1]))
        val supportingHeadline = compose.onNodeWithText(titles[1], useUnmergedTree = true)
        val supportingPhoto = supportingHeadline.onParent().onChildren().filter(hasTestTag("article-photo")).onFirst()
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
                if (width <= 400) 1 else 2, measuredColumns.get())
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
            // Espresso's default root picker can retain the unfocused activity root
            // while Compose Dialog owns focus. Inject a real Back key through Android
            // instead, so the focused dialog receives it without selecting a View root.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
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
                                    feedCount = 24, isExpanded = true)
                            }
                            item {
                                PublicationGrid(itemCount = 24) { index, modifier ->
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
        compose.runOnIdle { list.get().requestScrollToItem(20) }
        compose.onNodeWithTag("folder-open:Section 19").assertIsDisplayed()
        assertAboveBottomBar(compose.onNodeWithTag("folder-open:Section 19"))
        compose.onNodeWithTag("folder-expand:Section 19").performTouchInput { click() }
        assertEquals("An empty folder keeps its independent disclosure target", 3, disclosures.get())
        assertEquals(2, opened.get())
    }

    @Test fun articleSwipesScrollThenTurnOnlyTheConcretePageAtTheBoundary() {
        val opened = mutableListOf<Int>()
        val toggled = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val page = mutableStateOf(0)
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        val feed = Feed(id = "gestures", name = "Gesture Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        val fixture = (0 until 24).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "gesture-$index", date = Date(0),
                title = "Dispatch $index: reading the newspaper without accidental actions",
                rawDescription = "", shortDescription = "Substantial reporting keeps the current page taller than its actual available viewport, at every tested device width.",
                link = "https://example.com/$index", feedId = feed.id, accountId = 1), feed))
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
        assertAboveBottomBar(compose.onNodeWithText(fixture[11].articleWithFeed.article.title))
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
                        topBar = { Text("The daily edition", Modifier.fillMaxWidth().padding(16.dp)) },
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
                    list.get().requestScrollToItem(19)
                }
                val last = compose.onNodeWithTag("footer-article-19").assertIsDisplayed()
                assertAboveBottomBar(last)
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
                        Column(Modifier.width(320.dp)) {
                            ReadingLayoutPresetSetting()
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val layout = resolveReadingLayout(maxWidth.value, LocalDensity.current.fontScale,
                                    LocalReadingLayoutPreset.current.preset, LocalReadingColumns.current.columns)
                                SideEffect { columns.set(layout.columns) }
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
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            compose.onNode(hasText(ReadingColumnsPreference.Two.toDesc(context)) and isSelectable()).assertDoesNotExist()
            assertEquals(ReadingColumnsPreference.Auto, observed.get())
            assertEquals(ReadingColumnsPreference.Auto, runBlocking {
                ReadingColumnsPreference.fromPreferences(context.dataStore.data.first())
            })
            compose.runOnIdle { fontScale.value = 1.6f }
            compose.waitUntil(5_000) { columns.get() == 1 }
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

    @Test fun heldSwipeCannotTurnOrMarkThePageSelectedByAVolumeKey() {
        VolumeKeyEventBus.resetForTest()
        val page = mutableStateOf(0)
        val marked = mutableListOf<String>()
        val list = java.util.concurrent.atomic.AtomicReference<LazyListState>()
        val feed = Feed(id = "held", name = "Held Gesture Journal", url = "https://example.com",
            groupId = "g", accountId = 1)
        val fixture = (0 until 6).map { index ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "held-$index", date = Date(0),
                title = "Brief $index", rawDescription = "", shortDescription = "A short dispatch.",
                link = "https://example.com/$index", feedId = feed.id, accountId = 1), feed))
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
                link = "https://example.com/$index", feedId = feed.id, accountId = 1), feed))
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

    private fun waitForPhotoPixels() {
        // Inspect only the production image node, not page text. The original photograph
        // contains both broad black sky and blue Earth pixels, unlike an empty placeholder.
        compose.waitUntil(10_000) {
            val photos = compose.onAllNodesWithTag("article-photo", useUnmergedTree = true)
            if (photos.fetchSemanticsNodes().isEmpty()) return@waitUntil false
            val image = photos.onFirst().captureToImage().asAndroidBitmap()
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val sky = pixels.count { Color.red(it) < 30 && Color.green(it) < 30 && Color.blue(it) < 30 }
            val earth = pixels.count { Color.blue(it) > Color.red(it) + 8 && Color.blue(it) > Color.green(it) + 2 }
            sky > pixels.size / 10 && earth > 20
        }
    }

    /** Production chrome around deterministic content fixtures, not an account-backed app session. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FixtureChrome(subscriptions: Boolean, articleCount: Int = 5, content: @Composable () -> Unit) {
        var filter by remember { mutableStateOf(Filter.Unread) }
        RYScaffold(
            reserveBottomBarSpace = true,
            topBar = {
                TopAppBar(title = { if (!subscriptions) Text(filter.toName()) },
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
            },
            bottomBar = {
                if (subscriptions) {
                    FilterBar(modifier = Modifier.testTag("fixture-bottom-bar"), filter = filter,
                        filterBarStyle = FlowFilterBarStylePreference.Icon.value,
                        filterBarFilled = true, filterBarPadding = 12.dp, filterBarTonalElevation = 0.dp,
                        filterOnClick = { filter = it })
                } else {
                    EInkPaginationBar(modifier = Modifier.testTag("fixture-bottom-bar"),
                        currentPage = 1, totalPages = 1, totalArticles = articleCount, onPrev = {}, onNext = {})
                }
            },
        ) {
            Box(Modifier.fillMaxSize().testTag("fixture-content-viewport")) { content() }
        }
    }

    private fun assertAboveBottomBar(node: SemanticsNodeInteraction) {
        // Match the complete actionable block, not just a headline that could hide clipped metadata.
        val last = node.assertHasClickAction().getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("fixture-content-viewport").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("fixture-bottom-bar").getUnclippedBoundsInRoot()
        assertTrue("Production scaffold reserves the measured bottom bar", viewport.bottom <= bar.top + 1.dp)
        assertTrue("Complete last article/publication remains in the content viewport", last.top >= viewport.top - 1.dp && last.bottom <= viewport.bottom + 1.dp)
        assertTrue("Last content remains above bottom navigation", last.bottom <= bar.top + 1.dp)
    }

    private fun capture(name: String) {
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
        output.outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) {
                "Could not encode screenshot: $output"
            }
        }
        check(output.length() > 0) { "Empty screenshot: $output" }
    }
}
