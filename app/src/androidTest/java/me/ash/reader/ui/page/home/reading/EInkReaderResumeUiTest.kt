package me.ash.reader.ui.page.home.reading

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.ash.reader.domain.library.*
import me.ash.reader.ui.page.library.SavedLibraryReadingContent
import me.ash.reader.ui.page.library.SavedLibraryReadingState
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.preference.LocalReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderFormatting
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/** Real WebView and production controls, with only benign synthetic article content. */
class EInkReaderResumeUiTest {
    @get:Rule val testName = TestName()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("savedResumePhase")
    private val visible = mutableStateOf(true)
    private val itemId = mutableStateOf("synthetic-saved-A")
    private val revision = mutableStateOf("synthetic-body-v1")
    private val initialAnchor = mutableStateOf<String?>(null)
    private val pages = AtomicInteger()
    private val saves = AtomicInteger()
    private val exits = AtomicInteger()
    private val anchorAtExit = AtomicReference<String?>(null)
    private val anchors = CopyOnWriteArrayList<String>()
    private val pageEvents = CopyOnWriteArrayList<Int>()
    private val restoreResults = CopyOnWriteArrayList<String>()
    private val samples = JSONArray()
    private val edgeGeometry = JSONArray()
    private val body = (1..180).joinToString("") {
        "<p>Section $it. A saved reader keeps the same logical word through reopening, font changes and late image layout. " +
            "閱讀位置保留完整文字 😀 café. This synthetic fixture never contacts a feed or website.</p>"
    }
    private val fixtureFile get() = File(context.filesDir, "synthetic-reader-resume-fixture.json")

    @Before fun prepare() { VolumeKeyEventBus.resetForTest() }
    @After fun finish() {
        val report = JSONObject().put("phase", phase ?: "native").put("pid", android.os.Process.myPid()).put("snapshots", samples)
            .put("restoreResults", JSONArray(restoreResults.toList())).put("pageEvents", JSONArray(pageEvents.toList()))
            .put("edgeGeometry", edgeGeometry)
        if (samples.length() > 0 || pageEvents.isNotEmpty() || edgeGeometry.length() > 0)
            File(outputDirectory(), "reader-resume-${phase ?: testName.methodName}.json").writeText(report.toString(2))
        VolumeKeyEventBus.resetForTest()
    }

    @Test fun reopenRestoresExactAnchorAndStaleArticleCallbacksAreIgnored() {
        assumeTrue(phase == null)
        startReader()
        awaitReady()
        js("goToPage(Math.min(12, _totalPages - 1))")
        val before = snapshot("before-close")
        val saved = before.getString("envelope")
        val oldGeneration = before.getString("generation")
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        anchors.clear(); pageEvents.clear()
        compose.runOnIdle { initialAnchor.value = saved; pages.set(0); visible.value = true }
        awaitReady()
        val restored = snapshot("same-document-reopened")
        assertEquals(before.getJSONObject("anchor").toString(), restored.getJSONObject("anchor").toString())
        assertTrue(pageEvents.first() > 1)
        assertEquals("restored", restoreResults.last())
        assertAnchorVisible(restored)
        js("InkReadFormatting.beginSession(); InkReadFormatting.apply(Object.assign({}, window._inkreadInitialFormatting, {fontSize:32,lineHeight:1.8}))")
        compose.waitForIdle()
        val reformatted = snapshot("restored-and-reformatted")
        assertEquals(before.getJSONObject("anchor").toString(), reformatted.getJSONObject("anchor").toString())
        assertAnchorVisible(reformatted)
        js("InkReadFormatting.endSession(true, window._inkreadInitialFormatting)")
        compose.runOnIdle { itemId.value = "synthetic-saved-B"; revision.value = "synthetic-body-v2"; pages.set(0) }
        awaitReady()
        assertEquals("changed", restoreResults.last())
        assertEquals(0, snapshot("changed-document-fallback").getInt("page"))
        js("Android.onReadingAnchor(${JSONObject.quote(oldGeneration)}, 'synthetic-saved-A', 'synthetic-body-v1', 'stale-sentinel')")
        compose.waitForIdle()
        assertFalse(anchors.contains("stale-sentinel"))
        capture("reopened-reader")
    }

    @Test fun resumeVisibilityDistinguishesLayoutMarginsFromViewportClipping() {
        assumeTrue(phase == null)
        // Native tablet evidence: the 18px line starts at y=24, while its font-em
        // Range spans y=22..43. All of it remains inside the actual WebView.
        fun sample(left: Double = 24.0, right: Double = 34.296875,
            top: Double = 22.0, bottom: Double = 43.0) = JSONObject()
            .put("width", 1000).put("height", 1173)
            .put("margins", JSONObject().put("left", 24).put("right", 24).put("top", 24).put("bottom", 24))
            .put("rect", JSONObject().put("left", left).put("right", right).put("top", top).put("bottom", bottom))
        assertTrue("Font-em overhang into blank layout margin is not clipping", anchorIsOnVisiblePage(sample()))
        assertFalse("Left viewport clipping must fail", anchorIsOnVisiblePage(sample(left = -2.0)))
        assertFalse("Right viewport clipping must fail", anchorIsOnVisiblePage(sample(right = 1002.0)))
        assertFalse("Top viewport clipping must fail", anchorIsOnVisiblePage(sample(top = -2.0)))
        assertFalse("Bottom viewport clipping must fail", anchorIsOnVisiblePage(sample(bottom = 1175.0)))
        assertFalse("A different column must fail", anchorIsOnVisiblePage(sample(left = 1024.0, right = 1034.296875)))
        assertFalse("A box wholly in the blank margin is not an article anchor", anchorIsOnVisiblePage(sample(top = 1.0, bottom = 20.0)))
        assertFalse("An empty Range must fail", anchorIsOnVisiblePage(sample(right = 24.0)))
    }

    @Test fun onDemandSaveHasFortyEightDpTargetAndBackExportsBeforeExit() {
        assumeTrue(phase == null)
        startReader()
        awaitReady()
        compose.onNodeWithTag("reader_save_article").assertDoesNotExist()
        compose.onNodeWithTag("reader_controls_toggle").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("reader_save_article").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("reader_save_article").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reader_save_article").performClick()
        assertEquals(1, saves.get())
        pressBack()
        compose.onNodeWithTag("reader_save_article").assertDoesNotExist()
        assertEquals(0, exits.get())
        js("goToPage(Math.min(5, _totalPages - 1))")
        val expected = snapshot().getJSONObject("anchor").toString()
        compose.waitUntil(5_000) { anchors.any { JSONObject(it).getJSONObject("anchor").toString() == expected } }
        compose.runOnIdle { anchors.clear(); anchorAtExit.set(null) }
        pressBack()
        compose.waitUntil(5_000) { exits.get() == 1 }
        // A page-turn callback or a later disposal export cannot satisfy the exit ordering.
        assertEquals(expected, JSONObject(requireNotNull(anchorAtExit.get())).getJSONObject("anchor").toString())
    }

    @Test fun savedReaderZeroMarginsStayInsideSystemBarsWithoutDoubleToolbarInsets() {
        assumeTrue(phase == null)
        instrumentation.runOnMainSync { compose.activity.enableEdgeToEdge() }
        val item = SavedItem("synthetic-insets", "https://example.invalid/saved", "Saved inset fixture",
            "Fixture author", "Fixture source", null, 0L, SavedLocation.INBOX, false,
            SavedContentState.READY, SavedImageState.NONE, null, null)
        val state = SavedLibraryReadingState(itemId = item.id, item = item,
            document = SavedReadingDocument(item, body, "synthetic-insets-v1", null), loading = false)
        compose.setContent {
            CompositionLocalProvider(LocalReaderFormatting provides ReaderFormatting.Default.copy(
                marginTop = 0, marginBottom = 0, marginLeft = 0, marginRight = 0)) {
                SavedLibraryReadingContent(state, onBack = { exits.incrementAndGet() },
                    onMove = {}, onSetRead = {}, onRetry = {}, onReadingAnchorChanged = { _, _, _ -> })
            }
        }
        awaitSavedReady()
        val initial = snapshot("saved-zero-margins")
        assertEquals(0.0, initial.getJSONObject("margins").getDouble("top"), .01)
        assertEquals(0.0, initial.getJSONObject("margins").getDouble("bottom"), .01)
        assertTrue("Capture time must not look like an unlabeled publication date",
            (JSONTokener(js("document.querySelector('.eink-meta').textContent")).nextValue() as String).contains(" · Saved "))
        val safe = safeScreenBounds()
        val article = webViewBounds()
        assertInsideSafeBounds("zero-margin-article", article, safe)
        assertEquals("No extra top inset", safe.top, article.top, 1f)
        assertEquals("Only the 3dp progress line sits below the hidden-controls viewport",
            safe.bottom - 3 * context.resources.displayMetrics.density, article.bottom, 1f)
        js("goToPage(Math.min(5, _totalPages - 1))")
        snapshot("saved-zero-margins-later-page")
        assertInsideSafeBounds("zero-margin-later-page", webViewBounds(), safe)
        capture("saved-zero-margins")
        compose.onNodeWithTag("reader_controls_toggle").performClick()
        // Await the shown footer before checking its unchanged touch-target and inset bounds.
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("reader_library_actions").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("reader_library_actions").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val controls = nodeScreenBounds("reader_secondary_controls")
        assertInsideSafeBounds("saved-toolbar", controls, safe)
        assertEquals("Consumed outer insets must not be added again below the toolbar",
            safe.bottom - 4 * context.resources.displayMetrics.density, controls.bottom, 1f)
        assertEquals("Showing controls must not change document generation",
            initial.getString("generation"), snapshot().getString("generation"))
        capture("saved-zero-margins-controls")
    }

    @Test fun savedLoadingAndErrorBackRemainInsideSystemInsets() {
        assumeTrue(phase == null)
        instrumentation.runOnMainSync { compose.activity.enableEdgeToEdge() }
        val state = mutableStateOf(SavedLibraryReadingState(itemId = "synthetic-loading"))
        compose.setContent {
            SavedLibraryReadingContent(state.value, onBack = { exits.incrementAndGet() },
                onMove = {}, onSetRead = {}, onRetry = {}, onReadingAnchorChanged = { _, _, _ -> })
        }
        compose.waitForIdle()
        val safe = safeScreenBounds()
        compose.onNodeWithTag("saved_reader_back").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertInsideSafeBounds("loading-back", nodeScreenBounds("saved_reader_back"), safe)
        compose.onNodeWithTag("saved_reader_back").performClick()
        assertEquals(1, exits.get())
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = "Synthetic offline failure") }
        compose.onNodeWithTag("saved_reader_back").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertInsideSafeBounds("error-back", nodeScreenBounds("saved_reader_back"), safe)
        compose.onNodeWithTag("saved_reader_back").performClick()
        assertEquals(2, exits.get())
        capture("saved-error-insets")
    }

    /** Run this method in distinct instrumentation processes, seed then restore. */
    @Test fun freshProcessResume() {
        assumeTrue(phase == "seed" || phase == "restore")
        if (phase == "restore") {
            assertTrue("A prior seed process must create the fixture", fixtureFile.isFile)
            initialAnchor.value = fixtureFile.readText()
        }
        startReader()
        awaitReady()
        if (phase == "seed") {
            js("goToPage(Math.min(12, _totalPages - 1))")
            val state = snapshot("seed-position")
            assertTrue(state.getInt("page") > 0)
            fixtureFile.writeText(state.getString("envelope"))
            capture("resume-seed")
        } else {
            val expected = JSONObject(initialAnchor.value!!).getJSONObject("anchor").toString()
            val state = snapshot("fresh-process-restored")
            assertEquals("restored", restoreResults.last())
            assertEquals(expected, state.getJSONObject("anchor").toString())
            assertTrue("Native controller must never announce initial page zero", pageEvents.first() > 1)
            assertAnchorVisible(state)
            assertTrue(anchors.all { JSONObject(it).getJSONObject("anchor").toString() == expected })
            capture("resume-restored")
            assertTrue(fixtureFile.delete())
        }
    }

    private fun startReader() {
        compose.setContent {
            CompositionLocalProvider(LocalReaderFormatting provides ReaderFormatting.Default) {
                Box(Modifier.fillMaxSize().testTag("resume_reader_fixture")) {
                    if (visible.value) EInkPaginatedContent(
                        content = body, articleId = itemId.value, feedName = "Synthetic Saved", title = "Durable reader fixture",
                        publishedDate = Date(0), readingContentRevision = revision.value, initialReadingAnchor = initialAnchor.value,
                        onPageChanged = { page, total -> pageEvents += page; pages.set(total) },
                        onReadingAnchorChanged = { _, _, json -> anchors += json },
                        onReadingAnchorRestore = { restoreResults += it },
                        onSaveArticle = { saves.incrementAndGet() },
                        onBack = { anchorAtExit.set(anchors.lastOrNull()); exits.incrementAndGet(); visible.value = false },
                    )
                }
            }
        }
    }

    private fun awaitSavedReady() {
        compose.waitUntil(15_000) { findWebView(compose.activity.window.decorView) != null }
        compose.waitUntil(15_000) {
            js("typeof _didFinishInitialPagination !== 'undefined' && _didFinishInitialPagination") == "true"
        }
        compose.waitForIdle()
    }

    private fun safeScreenBounds(): Rect {
        var result = Rect.Zero
        instrumentation.runOnMainSync {
            val decor = compose.activity.window.decorView
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertTrue("Edge-to-edge fixture must expose real system insets", insets.top + insets.bottom > 0)
            result = Rect((origin[0] + insets.left).toFloat(), (origin[1] + insets.top).toFloat(),
                (origin[0] + decor.width - insets.right).toFloat(), (origin[1] + decor.height - insets.bottom).toFloat())
        }
        return result
    }

    private fun webViewBounds(): Rect {
        var result = Rect.Zero
        instrumentation.runOnMainSync {
            val view = checkNotNull(findWebView(compose.activity.window.decorView))
            val origin = IntArray(2).also(view::getLocationOnScreen)
            result = Rect(origin[0].toFloat(), origin[1].toFloat(),
                (origin[0] + view.width).toFloat(), (origin[1] + view.height).toFloat())
        }
        return result
    }

    private fun nodeScreenBounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val origin = IntArray(2)
        instrumentation.runOnMainSync { (node.root as ViewRootForTest).view.getLocationOnScreen(origin) }
        val bounds = node.boundsInRoot
        return Rect(bounds.left + origin[0], bounds.top + origin[1], bounds.right + origin[0], bounds.bottom + origin[1])
    }

    private fun assertInsideSafeBounds(label: String, bounds: Rect, safe: Rect) {
        assertTrue("$label left", bounds.left >= safe.left - 1)
        assertTrue("$label top", bounds.top >= safe.top - 1)
        assertTrue("$label right", bounds.right <= safe.right + 1)
        assertTrue("$label bottom", bounds.bottom <= safe.bottom + 1)
        fun Rect.json() = JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom)
        edgeGeometry.put(JSONObject().put("label", label).put("bounds", bounds.json()).put("safe", safe.json()))
    }

    private fun awaitReady() {
        compose.waitUntil(15_000) { pages.get() > 5 }
        compose.waitForIdle()
        assertTrue(snapshot().getBoolean("ready"))
    }

    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            checkNotNull(findWebView(compose.activity.window.decorView)).evaluateJavascript(script) {
                result.set(it); latch.countDown()
            }
        }
        assertTrue("WebView evaluation timed out", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun snapshot(label: String? = null): JSONObject {
        val raw = js("""JSON.stringify((function(){
            var a=InkReadFormatting.currentAnchor(),f=document.getElementById('eink-content');
            var w=document.createTreeWalker(f,NodeFilter.SHOW_TEXT,{acceptNode:function(n){return n.parentElement && !/^(SCRIPT|STYLE|NOSCRIPT)$/.test(n.parentElement.tagName) && /\S/.test(n.data)?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_REJECT;}}), ns=[],n;
            while(n=w.nextNode())ns.push(n);
            var r=document.createRange();r.setStart(ns[a.node],a.offset);r.setEnd(ns[a.node],Math.min(ns[a.node].length,a.offset+1));var b=r.getBoundingClientRect();
            return {generation:window._inkreadGeneration,ready:_didFinishInitialPagination,page:_currentPage,anchor:a,envelope:InkReadFormatting.exportReadingAnchor(),
                rect:{left:b.left,right:b.right,top:b.top,bottom:b.bottom},margins:InkReadFormatting.effectiveMargins(),width:_vw,height:innerHeight};
        })())""")
        return JSONObject(JSONTokener(raw).nextValue() as String).also { if (label != null) samples.put(JSONObject(it.toString()).put("label", label)) }
    }

    private fun assertAnchorVisible(value: JSONObject) {
        assertTrue("The exact logical anchor must occupy this page without viewport clipping: $value",
            anchorIsOnVisiblePage(value))
    }

    private fun anchorIsOnVisiblePage(value: JSONObject): Boolean {
        val r = value.getJSONObject("rect"); val m = value.getJSONObject("margins")
        val left = r.getDouble("left"); val right = r.getDouble("right")
        val top = r.getDouble("top"); val bottom = r.getDouble("bottom")
        val width = value.getDouble("width"); val height = value.getDouble("height")
        // CSS columns have overflow:visible. Margins position the line box; they
        // do not clip its font ascent/descent whitespace. Keep the complete Range
        // inside the actual WebView, with the original subpixel tolerance, and
        // require overlap with the inset reading area. Exact node/offset identity
        // is asserted separately; the formatting suite retains native ink proofs.
        return right > left && bottom > top &&
            left >= -.75 && right <= width + .75 && top >= -.75 && bottom <= height + .75 &&
            right > m.getDouble("left") && left < width - m.getDouble("right") &&
            bottom > m.getDouble("top") && top < height - m.getDouble("bottom")
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = findWebView(compose.activity.window.decorView)
            // The loading/error inset fixtures deliberately contain no WebView.
            if (view == null) drawn.countDown()
            else view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    // Visual readiness promises the next draw, not an already displayed frame.
                    if (android.os.Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                        view.viewTreeObserver.registerFrameCommitCallback { drawn.countDown() }
                        view.invalidate()
                    } else {
                        view.postOnAnimation { view.postOnAnimation { drawn.countDown() } }
                        view.invalidate()
                    }
                }
            })
        }
        assertTrue("The screenshot must include the measured WebView page", drawn.await(10, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(outputDirectory(), "reader-$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun outputDirectory(): File {
        val supplied = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        return (if (supplied.isNullOrBlank()) File(context.getExternalFilesDir(null), "saved-library") else File(supplied)).apply { mkdirs() }
    }
}
