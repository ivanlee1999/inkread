package me.ash.reader.ui.page.library

import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.ash.reader.domain.library.*
import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.page.home.NativePageDensityCapture
import me.ash.reader.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * A single real-Compose harness runs at both accessibility scales on each CI AVD:
 * 320dp, 400dp, 1080px/420dpi fractional phone, 800dp and 1000dp. It does not draw
 * a mock HTML list or simulate width by shrinking typography. Only the repository data is fake;
 * SavedLibraryPage, capture host/dialog, measured newspaper and Saved reader are production UI.
 */
@RunWith(Parameterized::class)
class SavedLibraryNativeVisualTest(private val fontScale: Float) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stores = mutableListOf<ViewModelStore>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var photograph: File
    @Before fun resetKeys() {
        VolumeKeyEventBus.resetForTest()
        photograph = File(instrumentation.targetContext.cacheDir, "saved-native-photo-${java.util.UUID.randomUUID()}.jpg")
        instrumentation.context.assets.open("nasa-earthrise.jpg").use { input -> photograph.outputStream().use { output -> input.copyTo(output) } }
    }
    @After fun releaseModels() {
        compose.runOnUiThread { stores.forEach { it.clear() }; VolumeKeyEventBus.resetForTest() }
        if (::photograph.isInitialized) photograph.delete()
    }

    @Test fun everySavedPageFitsCompleteBlocksAndFinalMetadataAboveSystemInsets() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitEdition(fixture)
        val expected = fixture.repository.rows.value.filter { it.location == SavedLocation.INBOX }.map { it.id }
        val seen = mutableListOf<String>()
        val count = compose.runOnIdle { fixture.edition.pages.size }
        assertTrue("Mixed realistic sources must span more than one actual measured page", count > 1)
        repeat(count) { page ->
            seen += assertWholePage(fixture)
            if (page in setOf(0, count / 2, count - 1)) captureList("page-$page-of-$count", fixture)
            if (page < count - 1) nextPage()
        }
        assertEquals("Every Saved item is reachable exactly once in repository order", expected, seen)
        compose.onNodeWithTag("article-page-control").performClick()
        compose.onNodeWithTag("article-page-next").assertIsNotEnabled()
        compose.onNodeWithTag("article-page-close").performClick()
        val final = compose.runOnIdle { fixture.edition.currentPage }
        compose.onNodeWithTag("saved-library-viewport").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(final, fixture.edition.currentPage)
        assertTrue("List page turns never turn Saved locations into read state", fixture.repository.readChanges.isEmpty())
        assertTrue(fixture.repository.moves.isEmpty())
    }

    @Test fun longPressActionsKeepLocationIndependentFromReadAndCancelDoesNotAct() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitEdition(fixture)
        val id = assertWholePage(fixture).first()
        longPress(id)
        compose.onNodeWithTag("saved-library-item-actions").assertIsDisplayed()
        capture("item-actions", "saved-library-item-actions")
        val page = fixture.edition.currentPage
        emitVolume()
        assertEquals("A modal does not leave a hidden list pager active", page, fixture.edition.currentPage)
        pressDialogBack("saved-library-item-actions")
        assertTrue(fixture.repository.moves.isEmpty())
        assertTrue(fixture.repository.readChanges.isEmpty())
        longPress(id)
        compose.onNodeWithTag("saved-library-move-LATER").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.repository.rows.value.first { it.id == id }.location == SavedLocation.LATER }
        assertFalse(fixture.repository.rows.value.first { it.id == id }.isRead)
        compose.onNodeWithTag("saved-library-location-LATER").performClick()
        compose.waitUntil(5_000) { fixture.model.location.value == SavedLocation.LATER.name }
        compose.waitForIdle()
        compose.onNodeWithTag("saved-item-$id", useUnmergedTree = true).assertIsDisplayed()
        longPress(id)
        compose.onNodeWithTag("saved-library-toggle-read").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.repository.rows.value.first { it.id == id }.isRead }
        assertEquals(SavedLocation.LATER, fixture.repository.rows.value.first { it.id == id }.location)
        longPress(id)
        compose.onNodeWithTag("saved-library-remove").performScrollTo().performClick()
        compose.onNodeWithText("Keep article").performScrollTo().performClick()
        assertTrue("Cancelling destructive confirmation preserves the article", fixture.repository.rows.value.any { it.id == id })
        assertTrue(fixture.repository.removed.isEmpty())
        capture("later-read", "saved-library-viewport")
    }

    @Test fun savedReaderOwnsVolumeAndBackReturnsToTheSamePrintedLibraryPage() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        awaitEdition(fixture)
        nextPage()
        val page = fixture.edition.currentPage
        val ids = assertWholePage(fixture)
        clickArticle(ids.first())
        compose.onNodeWithTag("saved_library_reader").assertIsDisplayed()
        compose.waitUntil(15_000) { findWebView(compose.activity.window.decorView) != null }
        compose.waitUntil(20_000) { js("typeof _didFinishInitialPagination !== 'undefined' && _didFinishInitialPagination") == true }
        compose.waitUntil(5_000) { VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH) }
        val readerPage = (js("_currentPage") as Number).toInt()
        emitVolume()
        compose.waitUntil(5_000) { (js("_currentPage") as Number).toInt() > readerPage }
        assertEquals("Reader keys cannot turn the hidden Saved list", page, fixture.edition.currentPage)
        NativePageDensityCapture.capture(compose, "saved-reader-font-${fontLabel}", "saved_library_reader",
            recordDensity = false, contentBoundsOnScreen = ::readerBodyBounds, beforeCaptureFrame = ::awaitWebViewFrame)
        // The Activity capture host can overlay a live reader. Both choice and success are
        // actual dialogs, and neither may leak a hardware-page event to its covered WebView.
        val coveredPage = (js("_currentPage") as Number).toInt()
        compose.runOnIdle { fixture.capture.enqueue("https://example.test/reader-share") }
        compose.onNodeWithTag("saved-capture-dialog").assertIsDisplayed()
        emitVolume()
        assertEquals(coveredPage, (js("_currentPage") as Number).toInt())
        compose.onNodeWithTag("saved-capture-save").performScrollTo().performClick()
        compose.onNodeWithTag("saved-capture-success").assertIsDisplayed()
        emitVolume()
        assertEquals(coveredPage, (js("_currentPage") as Number).toInt())
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitForIdle()
        emitVolume()
        compose.waitUntil(5_000) { (js("_currentPage") as Number).toInt() > coveredPage }
        compose.onNodeWithTag("reader_controls_toggle").performClick()
        compose.onNodeWithTag("reader_library_actions").assertIsDisplayed().performClick()
        compose.onNodeWithTag("saved_reader_actions").assertIsDisplayed()
        val actionsPage = (js("_currentPage") as Number).toInt()
        emitVolume()
        assertEquals("The actions panel absorbs page keys", actionsPage, (js("_currentPage") as Number).toInt())
        pressSystemBack()
        compose.onNodeWithTag("saved_reader_actions").assertDoesNotExist()
        compose.onNodeWithTag("saved_library_reader").assertIsDisplayed()
        // Reader controls consume Back before route Back. Do not bypass that production stack.
        repeat(3) {
            if (fixture.readerId != null) pressSystemBack()
        }
        compose.waitUntil(5_000) { fixture.readerId == null }
        compose.waitUntil(5_000) { VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW) }
        assertEquals(page, fixture.edition.currentPage)
        assertEquals(ids, assertWholePage(fixture))
        assertEquals("Back never pops the enclosing app route", 0, fixture.outerBacks)
        captureList("reader-return", fixture)
    }

    @Test fun onboardingHoldsSharedLinksThenExplicitChoiceAndCancelUseRealDialog() {
        val fixture = Fixture()
        fixture.ready = false
        fixture.capture.enqueue("Read https://example.test/one and https://example.test/two", "Shared choices")
        compose.setContent { fixture.Screen() }
        compose.waitForIdle()
        compose.onNodeWithTag("saved-capture-dialog").assertDoesNotExist()
        assertTrue(fixture.repository.savedUrls.isEmpty())
        compose.runOnIdle { fixture.ready = true }
        compose.onNodeWithTag("saved-capture-dialog").assertIsDisplayed()
        compose.onNodeWithTag("saved-capture-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("saved-capture-subscribe").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("saved-capture-url-1").performScrollTo().performClick()
        capture("shared-choices", "saved-capture-dialog")
        compose.onNodeWithTag("saved-capture-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.savedResults.isNotEmpty() }
        assertEquals(listOf("https://example.test/two"), fixture.repository.savedUrls)
        assertTrue(fixture.subscriptions.isEmpty())
        compose.onNodeWithTag("saved-capture-success").assertIsDisplayed()
        capture("saved-confirmation", "saved-capture-success")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithTag("saved-capture-success").assertDoesNotExist()
        compose.runOnIdle { fixture.capture.enqueue("https://example.test/three") }
        compose.onNodeWithTag("saved-capture-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("saved-capture-dialog").assertDoesNotExist()
        assertEquals(1, fixture.repository.savedUrls.size)
        compose.runOnIdle { fixture.capture.enqueue("https://example.test/feed") }
        compose.onNodeWithTag("saved-capture-subscribe").performScrollTo().performClick()
        assertEquals(listOf("https://example.test/feed"), fixture.subscriptions)
        assertEquals("Subscription choice never saves an article", 1, fixture.repository.savedUrls.size)
    }

    private inner class Fixture {
        val repository = UiRepository(fixtures(photograph.absolutePath))
        val model = SavedLibraryViewModel(repository, SavedStateHandle()).also(::retain)
        val capture = SavedCaptureViewModel(repository, SavedStateHandle()).also(::retain)
        val edition get() = model.edition("${model.location.value}_${model.readFilter.value}")
        var readerId by mutableStateOf<String?>(null)
        var ready by mutableStateOf(true)
        var outerBacks = 0
        val savedResults = mutableListOf<String>()
        val subscriptions = mutableListOf<String>()
        @Composable fun Screen() {
            val nativeDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(nativeDensity, fontScale),
                LocalEInkMode provides EInkModePreference.ON,
                LocalFlowArticleListImage provides FlowArticleListImagePreference.ON,
                LocalFlowArticleListFeedIcon provides FlowArticleListFeedIconPreference.OFF,
                LocalFlowArticleListFeedName provides FlowArticleListFeedNamePreference.ON,
            ) {
                AppTheme(useDarkTheme = false) {
                    Box(Modifier.fillMaxSize().testTag("saved-test-window")) {
                        val id = readerId
                        if (id == null) SavedLibraryPage(onBack = { outerBacks++ }, onOpen = { readerId = it },
                            onCapture = { capture.enqueue(it, mode = LinkCaptureMode.SAVE) }, viewModel = model)
                        else {
                            val item = repository.rows.value.first { it.id == id }
                            SavedLibraryReadingContent(
                                state = SavedLibraryReadingState(id, item,
                                    SavedReadingDocument(item, READER_HTML, "fixture-text-v1", null), loading = false),
                                onBack = { readerId = null }, onMove = {}, onSetRead = {}, onRetry = {},
                                onReadingAnchorChanged = { _, _, _ -> },
                            )
                        }
                        SavedCaptureHost(capture, ready, onSubscribe = { subscriptions += it }, onSaved = { savedResults += it })
                    }
                }
            }
        }
    }

    private fun retain(model: androidx.lifecycle.ViewModel) {
        stores += ViewModelStore().also { it.put("model", model) }
    }

    private fun awaitEdition(fixture: Fixture) {
        compose.waitUntil(15_000) { fixture.edition.pages.isNotEmpty() }
        compose.waitForIdle()
        assertWholePage(fixture)
    }

    private fun assertWholePage(fixture: Fixture): List<String> {
        compose.waitForIdle()
        val expected = compose.runOnIdle {
            fixture.edition.pages[fixture.edition.currentPage].indices.map { fixture.edition.items[it].id }
        }
        val viewportNode = compose.onNodeWithTag("saved-library-viewport").fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("saved-library-viewport").getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("saved-test-window").getUnclippedBoundsInRoot()
        assertTrue(viewport.bottom <= root.bottom)
        assertTrue(fixture.edition.pages[fixture.edition.currentPage].heightPx <= viewportNode.boundsInRoot.height)
        val headerTabs = compose.onAllNodes(SemanticsMatcher("Saved location tabs") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("saved-library-location-")
        }).fetchSemanticsNodes()
        assertTrue("Reading starts below all wrapped accessibility tabs", headerTabs.all { it.boundsInRoot.bottom <= viewportNode.boundsInRoot.top })
        val cards = compose.onAllNodes(SemanticsMatcher("Placed saved article") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("saved-item-")
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(expected, cards.map { it.config[SemanticsProperties.TestTag].removePrefix("saved-item-") })
        for (id in expected) {
            val itemTag = "saved-item-$id"
            val card = compose.onNodeWithTag(itemTag, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("The complete $id block, including offline status, fits the printed page: $card / $viewport",
                card.top >= viewport.top && card.bottom <= viewport.bottom && card.left >= viewport.left && card.right <= viewport.right)
            assertTrue(card.bottom > card.top)
            val texts = compose.onAllNodes(hasAnyAncestor(hasTestTag(itemTag)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            texts.fetchSemanticsNodes().indices.forEach { index ->
                val child = texts[index].getUnclippedBoundsInRoot()
                assertTrue("No footer/status/text may overflow its whole block", child.bottom <= card.bottom && child.top >= card.top)
                val layouts = mutableListOf<TextLayoutResult>()
                texts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.forEach { assertEquals("Actual text honors the requested accessibility scale", fontScale, it.layoutInput.density.fontScale, 0f) }
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyAncestor(hasTestTag("saved-library-viewport")), useUnmergedTree = true).assertCountEquals(0)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val view = (viewportNode.root as ViewRootForTest).view
            compose.runOnUiThread {
                val insets = view.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
                val window = compose.activity.windowManager.currentWindowMetrics
                val origin = IntArray(2); view.getLocationOnScreen(origin)
                assertTrue("The final row ends above Android's actual navigation inset",
                    origin[1] + viewportNode.boundsInRoot.bottom <= window.bounds.bottom - insets.bottom + 1f)
            }
        }
        return expected
    }

    private fun nextPage() {
        compose.onNodeWithTag("article-page-control").performClick()
        compose.onNodeWithTag("article-page-next").performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun clickArticle(id: String) = compose.onNode(hasTestTag("article-headline") and hasAnyAncestor(hasTestTag("saved-item-$id")), useUnmergedTree = true)
        .performTouchInput { click(center) }
    private fun longPress(id: String) = compose.onNode(hasTestTag("article-headline") and hasAnyAncestor(hasTestTag("saved-item-$id")), useUnmergedTree = true)
        .performTouchInput { longClick(center) }
    private fun emitVolume() { compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }; compose.waitForIdle() }
    private fun pressSystemBack() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun pressDialogBack(tag: String) {
        val root = compose.onNodeWithTag(tag).fetchSemanticsNode().root as ViewRootForTest
        compose.waitUntil(5_000) { compose.runOnUiThread { root.view.hasWindowFocus() } }
        pressSystemBack()
        compose.waitUntil(5_000) { compose.runOnUiThread { !root.view.isAttachedToWindow } }
    }
    private val fontLabel get() = (fontScale * 100).toInt()
    private fun capture(name: String, tag: String) = NativePageDensityCapture.capture(compose, "saved-library-$name-font-$fontLabel", tag, recordDensity = false)
    private fun captureList(name: String, fixture: Fixture) {
        awaitLocalPhotographs()
        capture(name, "saved-library-viewport")
        val argument = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = if (argument.isNullOrBlank()) File(instrumentation.targetContext.getExternalFilesDir(null), "newspaper-screenshots") else File(argument)
        val viewport = compose.onNodeWithTag("saved-library-viewport").fetchSemanticsNode().boundsInRoot
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val report = JSONObject().put("schema", 1).put("productionSurface", "SavedLibraryPage")
            .put("fontScale", fontScale).put("density", metrics.density)
            .put("screenWidthDp", instrumentation.targetContext.resources.configuration.screenWidthDp)
            .put("viewportPx", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom)))
            .put("page", fixture.edition.currentPage).put("pageCount", fixture.edition.pages.size)
            .put("sourceIds", JSONArray(assertWholePage(fixture)))
        File(directory, "saved-library-$name-font-$fontLabel-${metrics.widthPixels}.geometry.json").writeText(report.toString(2))
    }
    private fun awaitLocalPhotographs() {
        val placed = SemanticsMatcher("Placed Saved source") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("saved-item-")
        }
        val photos = compose.onAllNodes(hasTestTag("article-photo") and hasAnyAncestor(placed), useUnmergedTree = true)
        val nodes = photos.fetchSemanticsNodes()
        if (nodes.isEmpty()) return
        compose.waitUntil(10_000) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { nodes.all { node ->
                val origin = IntArray(2)
                compose.runOnUiThread { (node.root as ViewRootForTest).view.getLocationOnScreen(origin) }
                val bounds = node.boundsInRoot
                var blue = 0
                for (y in (bounds.top.toInt() + origin[1]).coerceAtLeast(0) until (bounds.bottom.toInt() + origin[1]).coerceAtMost(bitmap.height)) {
                    for (x in (bounds.left.toInt() + origin[0]).coerceAtLeast(0) until (bounds.right.toInt() + origin[0]).coerceAtMost(bitmap.width)) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.blue(pixel) > Color.red(pixel) + 8 && Color.blue(pixel) > Color.green(pixel) + 2) blue++
                    }
                }
                blue > 20
            } } finally { bitmap.recycle() }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun js(script: String): Any {
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { requireNotNull(findWebView(compose.activity.window.decorView)).evaluateJavascript(script) { result.set(it); latch.countDown() } }
        assertTrue("Production Saved reader WebView must answer", latch.await(10, TimeUnit.SECONDS))
        return JSONTokener(result.get()).nextValue()
    }
    private fun awaitWebViewFrame() {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { requireNotNull(findWebView(compose.activity.window.decorView)).postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { latch.countDown() }
        }) }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
    }
    private fun readerBodyBounds(): Rect {
        var bounds: Rect? = null
        instrumentation.runOnMainSync {
            val view = requireNotNull(findWebView(compose.activity.window.decorView))
            val origin = IntArray(2); view.getLocationOnScreen(origin)
            bounds = Rect(origin[0] + view.width * .08f, origin[1] + view.height * .35f,
                origin[0] + view.width * .92f, origin[1] + view.height * .85f)
        }
        return requireNotNull(bounds)
    }

    private class UiRepository(items: List<SavedItem>) : SavedLibraryRepository {
        val rows = MutableStateFlow(items)
        val savedUrls = mutableListOf<String>()
        val readChanges = mutableListOf<Pair<String, Boolean>>()
        val moves = mutableListOf<Pair<String, SavedLocation>>()
        val removed = mutableListOf<String>()
        override fun observeItems(location: SavedLocation?): Flow<List<SavedItem>> = rows.map { values -> values.filter { location == null || it.location == location } }
        override fun observeItem(id: String): Flow<SavedItem?> = rows.map { values -> values.firstOrNull { it.id == id } }
        override suspend fun saveUrl(url: String, titleHint: String?): String {
            savedUrls += url
            return rows.value.firstOrNull { it.url == url }?.id ?: "captured-${savedUrls.size}"
        }
        override suspend fun saveRssSnapshot(url: String, title: String, author: String?, sourceName: String?, html: String): String = error("No RSS action in Saved UI")
        override suspend fun loadDocument(id: String): SavedReadingDocument? = error("Reader visual state is explicitly supplied")
        override suspend fun move(id: String, location: SavedLocation) {
            moves += id to location; rows.value = rows.value.map { if (it.id == id) it.copy(location = location) else it }
        }
        override suspend fun setRead(id: String, value: Boolean) {
            readChanges += id to value; rows.value = rows.value.map { if (it.id == id) it.copy(isRead = value) else it }
        }
        override suspend fun saveReadingAnchor(id: String, contentRevision: String, anchorJson: String) = error("Resume persistence is covered by repository and reader suites")
        override suspend fun retry(id: String) = Unit
        override suspend fun remove(id: String) { removed += id; rows.value = rows.value.filterNot { it.id == id } }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "fontScale={0}") fun scales() = listOf(arrayOf<Any>(1f), arrayOf<Any>(1.6f))
        private fun fixtures(photo: String) = (0 until 31).map { index -> SavedItem(
            id = "saved-$index", url = "https://example.test/article-$index",
            title = when (index % 3) {
                0 -> "城市图书馆重新开放：阅读、记忆与共同生活的空间 $index"
                1 -> "How patient observation changed a small city's public spaces $index"
                else -> "A dispatch from the coast $index"
            }, author = if (index % 4 == 0) null else "Ada Chen", sourceName = "The Independent Journal",
            excerpt = "城市图书馆将重新开放。The restoration preserves quiet corners, daylight and the stories of residents. ".repeat(2 + index % 4),
            savedAt = 1_700_000_000_000L - index * 60_000,
            location = if (index < 27) SavedLocation.INBOX else if (index < 29) SavedLocation.LATER else SavedLocation.ARCHIVE,
            isRead = index % 7 == 6, contentState = SavedContentState.READY,
            imageState = if (index % 4 == 0) SavedImageState.READY else SavedImageState.NONE,
            thumbnailPath = if (index % 4 == 0) photo else null, lastError = null,
        ) }
        private val READER_HTML = "<article>" + (0 until 100).joinToString("") { index ->
            "<p>Paragraph $index. The city library preserves a quiet place for reading and reflection. 城市图书馆保存着居民的故事，阅读是一场耐心而长久的旅程。</p>"
        } + "</article>"
    }
}
