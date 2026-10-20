package me.ash.reader.ui.page.home

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
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
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
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
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
        if (width < 440) assertTrue("Narrow reading order is vertical", second.top >= first.bottom)
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
        val titles = listOf("A quieter way to read the world", "Architecture for a changing climate", "The long view: why patient observation matters in science and everyday life", "世界新闻：城市中的自然", "An independent perspective")
        val feed = Feed(id = "fixture", name = "The Morning Journal", url = "https://example.com", groupId = "g", accountId = 1)
        val items = listOf(ArticleFlowItem.Date("Today", false)) + titles.mapIndexed { index, title ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(id = "a$index", date = Date(0), title = title,
                rawDescription = "", shortDescription = "Thoughtful reporting, clear ideas and a little room to think. An edition made for unhurried reading.",
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
                        val columns = if (maxWidth >= 600.dp) 2 else 1
                        LazyColumn(Modifier.testTag("article-list")) {
                            item { Text("The daily edition", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineLarge) }
                            ArticleList(pagingItems = articles, diffMap = emptyMap(), isShowFeedIcon = false,
                                isShowStickyHeader = false, articleListTonalElevation = 0, columns = columns,
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

    /** Production chrome around deterministic content fixtures, not an account-backed app session. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FixtureChrome(subscriptions: Boolean, articleCount: Int = 5, content: @Composable () -> Unit) {
        var filter by remember { mutableStateOf(Filter.Unread) }
        Scaffold(
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
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
        }
    }

    private fun assertAboveBottomBar(node: SemanticsNodeInteraction) {
        val last = node.fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("fixture-bottom-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("Last content remains above bottom navigation", last.bottom <= bar.top + 1f)
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
