package me.ash.reader.ui.page.adaptive

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.layout.resolveReadingLayout
import me.ash.reader.ui.page.home.NativePageDensityCapture
import me.ash.reader.ui.page.home.flow.*
import me.ash.reader.ui.page.home.reading.EInkPaginatedContent
import me.ash.reader.ui.page.home.reading.EInkReaderVolumeReservation
import me.ash.reader.ui.page.home.reading.ImageData
import me.ash.reader.ui.page.home.reading.ReaderImageViewer
import me.ash.reader.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real route scaffold, navigator, cleanup effects, measured newspaper and volume registrations. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class ReaderBackNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun resetKeys() = VolumeKeyEventBus.resetForTest()
    @After fun clearKeys() = VolumeKeyEventBus.resetForTest()

    @Test fun fullWindowAndLandscapeHeightReturnToTheExactPrintedPage() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
        assertFullWidth("route-list")
        NativePageDensityCapture.capture(compose, "route-list-full-width", "newspaper-viewport", recordDensity = false)
        for (shortWindow in listOf(false, true)) {
            compose.runOnIdle { fixture.shortWindow = shortWindow }
            compose.waitForIdle()
            assertFullWidth("route-list")
            compose.runOnIdle {
                assertTrue(fixture.edition.pages.size > 1)
                assertTrue(fixture.edition.turn(true, false) { error("Back must not mark a page") })
            }
            compose.waitForIdle()
            val before = compose.runOnIdle { fixture.frames.last() }
            val partition = compose.runOnIdle { fixture.edition.pages.toList() }
            openDisplayedArticle(fixture)
            assertFullWidth("route-reader")
            compose.runOnIdle {
                before.ids.forEach { fixture.edition.recordReadStatus(it, false) }
                fixture.edition.acceptSnapshot(fixture.pager, fixture.source.filterNot {
                    (it as ArticleFlowItem.Article).articleWithFeed.article.id in before.ids
                })
                fixture.frames.clear()
            }
            if (shortWindow) pressBack() else compose.onNodeWithContentDescription(backLabel).performClick()
            compose.waitForIdle()
            assertFullWidth("route-list")
            compose.runOnIdle {
                assertNull("Cleanup must outlive the disappearing detail pane", fixture.readerId)
                assertEquals(partition, fixture.edition.pages)
                assertTrue("Every returned draw retains the edition, not merely the settled frame", fixture.frames.isNotEmpty())
                fixture.frames.forEach {
                    assertEquals(before.page, it.page)
                    assertEquals(before.ids, it.ids)
                    assertEquals(before.columns, it.columns)
                }
                assertEquals(0, fixture.outerBacks)
                assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH))
            }
            NativePageDensityCapture.capture(compose,
                if (shortWindow) "route-list-landscape-return" else "route-list-reader-return",
                "newspaper-viewport", recordDensity = false)
        }
    }

    @Test fun imageModalDismissesBeforeReaderAndOnlyTheVisiblePagerOwnsVolume() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.waitUntil(5_000) { VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW) }
        openDisplayedArticle(fixture)
        compose.waitUntil(5_000) { VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH) }
        val page = fixture.edition.currentPage
        compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }
        compose.waitForIdle()
        assertEquals(page, fixture.edition.currentPage)
        compose.runOnIdle { fixture.showImage = true }
        compose.onNodeWithContentDescription("Modal fixture").assertIsDisplayed()
        pressBack()
        compose.waitForIdle()
        assertFalse(fixture.showImage)
        assertNotNull(fixture.readerId)
        assertEquals(0, fixture.outerBacks)
        pressBack()
        compose.waitUntil(5_000) { VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW) }
        assertNull(fixture.readerId)
        compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }
        compose.waitForIdle()
        assertEquals(page + 1, fixture.edition.currentPage)
        // Reopening must install a fresh HIGH owner/flow rather than leave an orphaned reservation.
        openDisplayedArticle(fixture)
        compose.waitUntil(5_000) { VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH) }
        pressBack()
        compose.waitUntil(5_000) { VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW) }
        assertEquals(0, fixture.outerBacks)
    }

    @Test fun staleAndRepeatedCloseCannotPopNewerArticleOrEnclosingRoute() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        openDisplayedArticle(fixture)
        val closeA = compose.runOnIdle { fixture.closeReader }
        compose.runOnIdle { fixture.open(ArticleData("route-55", 55)) }
        compose.waitForIdle()
        compose.runOnIdle { closeA() }
        compose.waitForIdle()
        assertEquals("route-55", fixture.readerId)
        val closeB = compose.runOnIdle { fixture.closeReader }
        compose.runOnIdle { fixture.open(ArticleData("route-56", 56)) }
        compose.waitForIdle()
        compose.runOnIdle { closeA(); closeB() }
        compose.waitForIdle()
        assertEquals("route-56", fixture.readerId)
        val closeC = compose.runOnIdle { fixture.closeReader }
        compose.runOnIdle { closeC(); closeC() }
        compose.waitForIdle()
        assertNull(fixture.readerId)
        assertEquals(ListDetailPaneScaffoldRole.List, fixture.navigator.currentDestination?.pane)
        assertEquals(0, fixture.outerBacks)
        compose.runOnIdle { closeA(); closeB(); closeC() }
        compose.waitForIdle()
        assertEquals(0, fixture.outerBacks)
        // Even reopening the same article creates a different destination; a detached callback
        // from the prior opening must not dismiss it. Then system Back skips all article history.
        compose.runOnIdle {
            fixture.open(ArticleData("route-54", 54))
            fixture.open(ArticleData("route-55", 55))
            fixture.open(ArticleData("route-56", 56))
        }
        compose.waitForIdle()
        compose.runOnIdle { closeC() }
        compose.waitForIdle()
        assertEquals("route-56", fixture.readerId)
        pressBack()
        compose.waitForIdle()
        assertNull(fixture.readerId)
        assertEquals(ListDetailPaneScaffoldRole.List, fixture.navigator.currentDestination?.pane)
        assertEquals(0, fixture.outerBacks)
    }

    @Test fun externalArticleStartsDirectlyAndSettingsReturnDoesNotReopenIt() {
        val fixture = Fixture(initialArticle = "route-4")
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
        assertEquals("route-4", fixture.readerId)
        assertTrue("External link must not flash the list before opening its article", fixture.frames.isEmpty())
        compose.runOnIdle { fixture.showSettings = true }
        compose.onNodeWithText("Settings fixture").assertIsDisplayed()
        compose.runOnIdle { fixture.showSettings = false }
        compose.waitForIdle()
        assertEquals("route-4", fixture.readerId)
        pressBack()
        compose.waitForIdle()
        assertNull(fixture.readerId)
        compose.runOnIdle { fixture.showSettings = true }
        compose.waitForIdle()
        compose.runOnIdle { fixture.showSettings = false }
        compose.waitForIdle()
        assertNull("A saved list destination must not re-run the initial deep-link open", fixture.readerId)
        assertFullWidth("route-list")
        // A different reading entry must not inherit the previous entry's navigator.
        compose.runOnIdle { fixture.initialArticle = null; fixture.entryId++ }
        compose.waitForIdle()
        assertNull(fixture.navigator.currentDestination?.contentKey)
        assertEquals(0, fixture.outerBacks)
    }

    @Test fun cancelledPredictiveBackKeepsArticleAndCommitClosesExactlyOnce() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        openDisplayedArticle(fixture)
        val article = fixture.readerId
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 100f, .7f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        assertEquals(article, fixture.readerId)
        assertEquals(0, fixture.outerBacks)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertNull(fixture.readerId)
        assertEquals(0, fixture.outerBacks)
    }

    @Test fun readerBackAppearsOnlyWithExistingControlsAndPreservesOtherControls() {
        val pages = AtomicInteger()
        var backs = 0
        compose.setContent {
            Theme {
                EInkPaginatedContent(
                    content = (1..80).joinToString("") { "<p>Reader controls stay compact and accessible. Story paragraph $it.</p>" },
                    title = "Reader Back regression", feedName = "The Daily Edition", publishedDate = Date(0),
                    onPageChanged = { _, total -> pages.set(total) }, onBack = { backs++ },
                )
            }
        }
        compose.waitUntil(15_000) { pages.get() > 1 }
        compose.onNodeWithContentDescription(backLabel).assertDoesNotExist()
        compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .9f)) }
        compose.onNodeWithContentDescription(backLabel).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Next page").assertIsDisplayed()
        compose.onNodeWithText("A↓").assertIsDisplayed()
        compose.onNodeWithText("A↑").assertIsDisplayed()
        NativePageDensityCapture.capture(compose, "route-reader-back-controls", "", recordDensity = false,
            contentBoundsOnScreen = ::documentBodyBounds, beforeCaptureFrame = ::awaitDocumentFrame)
        compose.onNodeWithContentDescription(backLabel).performClick()
        assertEquals(1, backs)
    }

    private val backLabel get() = compose.activity.getString(R.string.back)

    private fun awaitDocumentFrame() {
        val ready = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            requireNotNull(findWebView(compose.activity.window.decorView))
                .postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { ready.countDown() }
                })
        }
        assertTrue("The document body must reach visual readiness before capture", ready.await(10, TimeUnit.SECONDS))
    }

    private fun documentBodyBounds(): Rect {
        var bounds: Rect? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val webView = requireNotNull(findWebView(compose.activity.window.decorView))
            val origin = IntArray(2)
            webView.getLocationOnScreen(origin)
            check(webView.isShown && webView.width > 0 && webView.height > 0)
            // Controls/title/system bars must not disguise an unrendered white article body.
            bounds = Rect(origin[0] + webView.width * .08f, origin[1] + webView.height * .35f,
                origin[0] + webView.width * .92f, origin[1] + webView.height * .85f)
        }
        return requireNotNull(bounds)
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun assertFullWidth(tag: String) {
        val root = compose.onNodeWithTag("route-root").fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("$tag starts at the window edge", root.left, pane.left, 1f)
        assertEquals("$tag uses the whole window, not a reserved half-pane", root.right, pane.right, 1f)
    }

    private fun openDisplayedArticle(fixture: Fixture) {
        compose.waitForIdle()
        val id = compose.runOnIdle { fixture.edition.displayedArticles.first().article.id }
        // The placed source block contains the production SwipeableArticleItem click semantics.
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("article-block-$id")), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(id, fixture.readerId)
    }

    @Composable private fun Theme(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalEInkMode provides EInkModePreference.ON,
            LocalEInkEnglishFont provides 0,
            LocalFlowArticleListImage provides FlowArticleListImagePreference.OFF,
            LocalReadingColumns provides ReadingColumnsPreference.Auto,
            LocalReadingLayoutPreset provides ReadingLayoutPresetPreference.Auto,
        ) { AppTheme(useDarkTheme = false, content = content) }
    }

    private data class Frame(val columns: Int, val page: Int, val ids: List<String>)

    private inner class Fixture(initialArticle: String? = null) {
        val pager = Any()
        val source = articles()
        val edition = NewspaperViewportState().apply { acceptSnapshot(pager, source) }
        var readerId by mutableStateOf<String?>(null)
        var initialArticle by mutableStateOf(initialArticle)
        var entryId by mutableIntStateOf(0)
        var shortWindow by mutableStateOf(false)
        var showImage by mutableStateOf(false)
        var showSettings by mutableStateOf(false)
        var outerBacks = 0
        val frames = mutableListOf<Frame>()
        lateinit var navigator: ThreePaneScaffoldNavigator<ArticleData>
        lateinit var scope: CoroutineScope
        var closeReader: () -> Unit = {}

        fun open(article: ArticleData) {
            readerId = article.articleId // Same synchronous ownership claim as the production route.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, article)
            }
        }

        @Composable fun Content() {
            Theme {
                BackHandler { outerBacks++ }
                val holder = rememberSaveableStateHolder()
                Box(Modifier.fillMaxSize().testTag("route-root")) {
                    if (showSettings) Text("Settings fixture")
                    else holder.SaveableStateProvider(entryId) {
                        navigator = rememberArticleListNavigator(calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()), initialArticle)
                        scope = rememberCoroutineScope()
                        ArticleListReaderScaffold(
                            navigator = navigator,
                            modifier = Modifier.fillMaxHeight(if (shortWindow) .55f else 1f),
                            onDestinationChanged = { readerId = it?.articleId },
                            listPane = {
                                ArticleListPaneLayout(Modifier.testTag("route-list")) { layout ->
                                    EInkVolumePageKeys(enabled = true, state = edition,
                                        onTurn = { forward -> readerId == null && edition.turn(forward, false) {} })
                                    EInkNewspaperViewport(
                                        state = edition, columns = layout.columns,
                                        horizontalPadding = layout.horizontalPaddingDp.dp,
                                        columnSpacing = layout.columnSpacingDp.dp,
                                        onClick = { article, index -> open(ArticleData(article.article.id, index)) },
                                        modifier = Modifier.drawWithContent {
                                            drawContent()
                                            assertEquals(resolveReadingLayout(size.width / density).columns, layout.columns)
                                            frames += Frame(layout.columns, edition.currentPage, edition.displayedArticles.map { it.article.id })
                                        },
                                    )
                                }
                            },
                            detailPane = { onBack ->
                                SideEffect { closeReader = onBack }
                                val owner = remember(readerId) { Any() }
                                EInkReaderVolumeReservation(readerId != null, owner)
                                Box(Modifier.fillMaxSize().testTag("route-reader")) {
                                    Text(readerId.orEmpty())
                                    EInkPaginationBar(currentPage = 1, totalPages = 2, onPrev = {}, onNext = {},
                                        onBack = onBack, modifier = Modifier.align(Alignment.BottomCenter))
                                    if (showImage) ReaderImageViewer(ImageData("", "Modal fixture"), {}, { showImage = false })
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun articles(): List<ArticleFlowItem> {
        val feed = Feed("route-feed", "The Daily Edition", url = "https://example.com/feed", groupId = "route-group", accountId = 1)
        return (0 until 60).map { i ->
            ArticleFlowItem.Article(ArticleWithFeed(Article(
                id = "route-$i", date = Date((60 - i).toLong()), title = "Full-width newspaper story $i",
                rawDescription = "", shortDescription = "This retained edition keeps every original story on its printed page. ".repeat(3),
                link = "about:blank", feedId = feed.id, accountId = 1,
            ).apply { dateString = "09:30" }, feed))
        }
    }
}
