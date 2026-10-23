package me.ash.reader.ui.page.home.reading

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Region
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebView
import android.widget.ListView
import android.widget.TextView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed as isComposeDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.NoMatchingRootException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.dispatchEInkVolumeKey
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.infrastructure.preference.LocalReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderFormatting
import me.ash.reader.ui.theme.AppTheme
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.TypeSafeMatcher
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Android WebView long-press/ActionMode coverage; never replaced by JS Selection mocks. */
class EInkReaderAnnotationUiTest {
    @get:Rule val compose = createComposeRule()
    private val selecting = mutableStateOf(false)
    private val editing = mutableStateOf(false)
    private val annotationJson = mutableStateOf("[]")
    private val page = AtomicInteger()
    private val total = AtomicInteger()
    private val exits = AtomicInteger()
    private val previousArticles = AtomicInteger()
    private val nextArticles = AtomicInteger()
    private val linkOpens = AtomicInteger()
    private val created = AtomicReference<String?>()
    private val note = AtomicReference<Boolean?>()
    private val locations = AtomicReference<String?>()
    private var nativeOverflowOpen = false
    private var diagnosticAccessibilityFlags: Int? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun prepare() { VolumeKeyEventBus.resetForTest() }
    @After fun cleanup() {
        try {
            diagnosticAccessibilityFlags?.let { flags ->
                val automation = instrumentation.uiAutomation
                val info = automation.serviceInfo
                info.flags = flags
                automation.serviceInfo = info
                diagnosticAccessibilityFlags = null
            }
        } finally {
            VolumeKeyEventBus.resetForTest()
        }
    }

    private fun startReader(fontSize: Int = ReaderFormatting.Default.fontSize, annotationsAvailable: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalReaderFormatting provides ReaderFormatting.Default.copy(fontSize = fontSize)) {
                AppTheme(useDarkTheme = false) {
                    EInkPaginatedContent(modifier = Modifier.testTag("annotation-reader"),
                        articleId = "annotation-fixture", title = "Annotation fixture", feedName = "Local fixture",
                        publishedDate = Date(0), content = buildString {
                            append("<p id='select-target'>Selection preserves exact text, 中文 and emoji 😀.</p>")
                            append("<p><a id='link-target' href='https://example.invalid/annotation-fixture'>Fixture link</a></p>")
                            repeat(140) { append("<p id='paragraph-$it'>Paragraph $it. Long article text exercises native paging and annotation ownership.</p>") }
                        },
                        selectionMode = selecting.value, onSelectionModeChange = { selecting.value = it },
                        annotationEditing = editing.value, annotationsJson = annotationJson.value,
                        onCreateAnnotation = if (annotationsAvailable) {
                            { json, addNote -> created.set(json); note.set(addNote); editing.value = addNote }
                        } else null,
                        onAnnotationLocationsChanged = { locations.set(it) }, onAnnotationsClick = {},
                        onBack = { exits.incrementAndGet() },
                        onPrevArticle = { previousArticles.incrementAndGet() },
                        onNextArticle = { nextArticles.incrementAndGet() },
                        onLinkClick = { linkOpens.incrementAndGet() },
                        onPageChanged = { current, count -> page.set(current); total.set(count) })
                }
            }
        }
        compose.waitUntil(15_000) { total.get() > 2 }
        assertEquals(true, js("!!window.InkReadAnnotations"))
        compose.runOnIdle { assertFalse("The fixture must begin in normal reading mode", selecting.value) }
        compose.onNodeWithTag("reader_selection_done").assertDoesNotExist()
        awaitVisualFrame()
    }

    private fun openSelection() {
        compose.onNodeWithTag("reader_controls_toggle").performClick()
        awaitDisplayedControl("reader_select_text")
        compose.onNodeWithTag("reader_select_text").assertIsDisplayed().performClick()
        awaitDisplayedControl("reader_selection_done")
        compose.onNodeWithTag("reader_selection_done").assertIsDisplayed()
        compose.onNodeWithTag("reader_controls_toggle").assertDoesNotExist()
        compose.waitUntil(5_000) { selecting.value && js("window.innerHeight > 0") == true }
        // Wait for the fallback selection UI to reach a committed native frame.
        awaitVisualFrame()
    }

    private fun awaitDisplayedControl(tag: String) {
        // Semantics actions schedule Compose state changes. Wait for the actual
        // displayed node, not mere existence, without repeating either click.
        try {
            compose.waitUntil(5_000) { compose.onNodeWithTag(tag).isComposeDisplayed() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Reader control '$tag' was not displayed within 5000 ms", timeout)
        }
    }

    private data class NativeTouchTrace(val downAt: Long, val heldFor: Long, val events: String) {
        fun assertDelivered() {
            assertTrue("The real WebView must receive DOWN, not an overlay: $events", downAt >= 0)
            assertTrue("The native long-press must complete with UP: $events", events.contains("ACTION_UP"))
            assertFalse("The native selection stream must not be cancelled: $events", events.contains("ACTION_CANCEL"))
            assertTrue("Native DOWN must be held for Android's real long-press timeout: $events",
                heldFor >= ViewConfiguration.getLongPressTimeout())
        }
    }

    private fun longPressFirstWord(awaitSelection: Boolean = true): NativeTouchTrace {
        // This Range is read-only geometry. Only window-dispatched native touch
        // can create the Selection/ActionMode. No addRange or performLongClick.
        val point = textPoint("select-target", 0, 9)
        val downAt = AtomicLong(-1)
        val heldFor = AtomicLong(-1)
        val touchEvents = StringBuffer()
        instrumentation.runOnMainSync {
            reader().setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) downAt.set(event.eventTime)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    heldFor.set(event.eventTime - downAt.get())
                }
                touchEvents.append("${MotionEvent.actionToString(event.actionMasked)}@${event.eventTime}[${event.x},${event.y}] ")
                false // Observe only; production onTouchEvent owns the complete gesture.
            }
        }
        try {
            onView(isAssignableFrom(EInkAnnotationWebView::class.java)).perform(GeneralClickAction(
                Tap.LONG, { floatArrayOf(point.x, point.y) },
                Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0,
            ))
        } finally {
            instrumentation.runOnMainSync { reader().setOnTouchListener(null) }
            Log.i("InkReadAnnotationTest", "Native long-press delivered: heldMs=${heldFor.get()} events=$touchEvents selecting=${selecting.value}")
        }
        val trace = NativeTouchTrace(downAt.get(), heldFor.get(), touchEvents.toString())
        if (awaitSelection) {
            trace.assertDelivered()
            compose.waitUntil(5_000) { selecting.value && js("window.getSelection().toString().length > 0") == true }
            assertEquals("Selection", js("window.getSelection().toString()"))
            instrumentation.runOnMainSync {
                assertTrue("Native selection must own WebView focus", reader().hasFocus() && reader().hasWindowFocus())
            }
        }
        return trace
    }

    /** Kept source-compatible with the released reader for the old-fails/new-passes gate. */
    @Test fun defaultLongPressShowsNativeAnnotationToolbar() {
        startReader()
        prepareNativeCopyClipboard("Fixture before default-mode Copy")
        val before = page.get()
        val viewportBefore = js("JSON.stringify([innerWidth,innerHeight])")
        // Do not assert selectionEnabled/focus/DOWN first: the released overlay
        // must fail specifically because the native toolbar never appears.
        val trace = longPressFirstWord(awaitSelection = false)
        try {
            onView(withTagValue(equalTo<Any>("floating_toolbar")))
                .inRoot(nativeToolbarRoot()).check(matches(isDisplayed()))
        } catch (missing: NoMatchingRootException) {
            throw AssertionError("DEFAULT_SELECTION_TOOLBAR_MISSING: a native long-press in normal reading mode must show the annotation toolbar", missing)
        }
        trace.assertDelivered()
        compose.waitUntil(5_000) { selecting.value }
        assertEquals("Opening native selection must not turn a page", before, page.get())
        assertEquals("Selection must not resize the reading viewport", viewportBefore,
            js("JSON.stringify([innerWidth,innerHeight])"))
        assertTrue(nativeActionAvailable("Highlight"))
        closeNativeOverflow()
        assertTrue(nativeActionAvailable("Add note"))
        closeNativeOverflow()
        val exact = js("window.getSelection().toString()") as String
        assertEquals("Selection", exact)
        clickNativeAction("Copy")
        compose.waitUntil(5_000) { !selecting.value }
        assertNativeCopyResult(exact)
        assertEquals(true, js("window.getSelection().isCollapsed"))
        assertNull("Copy must not create an annotation", created.get())
        assertEquals(0, exits.get())
    }

    @Test fun defaultSelectionKeepsCopyButHidesUnavailableAnnotationActions() {
        startReader(annotationsAvailable = false)
        prepareNativeCopyClipboard("Fixture before Copy without annotation actions")
        val before = page.get()
        longPressFirstWord()
        assertTrue(nativeActionAvailable("Copy"))
        closeNativeOverflow()
        assertNativeAnnotationActionsAbsent()
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        settle()
        assertEquals("Selection without annotation storage still locks paging", before, page.get())
        volume(KeyEvent.KEYCODE_VOLUME_UP)
        settle()
        assertEquals(before, page.get())
        val exact = js("window.getSelection().toString()") as String
        clickNativeAction("Copy")
        compose.waitUntil(5_000) { !selecting.value }
        assertNativeCopyResult(exact)
        assertEquals("Copy without annotation storage must not turn pages", before, page.get())
        assertEquals(true, js("window.getSelection().isCollapsed"))
        assertEquals(0, exits.get())
        assertNull(created.get())
    }

    @Test fun realLongPressRetainsCopyAndBackLeavesSelectionBeforeReader() {
        startReader()
        longPressFirstWord()
        assertTrue("The native floating toolbar must keep Copy", nativeActionAvailable("Copy"))
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-native-selection", "annotation-reader", recordDensity = false,
            beforeCaptureFrame = ::awaitVisualFrame)
        closeNativeOverflow()
        val before = page.get()
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        settle()
        assertEquals("Volume Down cannot turn the page during native selection", before, page.get())
        volume(KeyEvent.KEYCODE_VOLUME_UP)
        settle()
        assertEquals("Volume Up cannot turn the page during native selection", before, page.get())
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
        assertEquals("First Back leaves selection, not the reader", 0, exits.get())
        compose.onNodeWithTag("reader_selection_done").assertDoesNotExist()
        assertEquals(true, js("window.getSelection().isCollapsed"))
        assertReadingNavigationRestored()
        pressBack()
        compose.waitUntil(5_000) { exits.get() == 1 }
    }

    @Test fun nativeAddNoteCapturesBeforeSelectionClearAndSuppressesEditingVolume() {
        startReader()
        longPressFirstWord()
        val exactBefore = js("window.getSelection().toString()") as String
        val rawBefore = js("window.getSelection().getRangeAt(0).toString()") as String
        clickNativeAction("Add note")
        compose.waitUntil(5_000) { created.get() != null }
        val saved = JSONObject(created.get()!!)
        assertEquals("Persist the exact range before native focus is cleared", exactBefore, saved.getString("quote"))
        assertEquals(rawBefore, saved.getString("rangeQuote"))
        assertEquals(true, note.get())
        assertEquals(1, saved.getInt("textMapVersion"))
        assertEquals(true, js("window.getSelection().isCollapsed"))
        compose.runOnIdle {
            assertTrue("Add note must transfer ownership to editing", editing.value)
            assertFalse("Capture must leave native selection mode", selecting.value)
        }
        val before = page.get()
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        settle()
        assertEquals(before, page.get())
        volume(KeyEvent.KEYCODE_VOLUME_UP)
        settle()
        assertEquals(before, page.get())
        assertEquals(0, exits.get())
        compose.runOnIdle { editing.value = false }
        // runOnIdle waits before its action, not for the recomposition it schedules.
        // Wait for the actual reader controls to return before injecting a raw key;
        // otherwise the still-active editing guard correctly consumes that press.
        compose.onNodeWithTag("reader_controls_toggle").assertIsDisplayed()
        assertReadingNavigationRestored()
    }

    @Test fun nativeHighlightRendersWithoutEditingBodyTextAndDoneRestoresPaging() {
        startReader()
        val before = js("document.getElementById('eink-content').innerHTML") as String
        longPressFirstWord()
        clickNativeAction("Highlight")
        compose.waitUntil(5_000) { created.get() != null }
        assertEquals(false, note.get())
        compose.runOnIdle { annotationJson.value = JSONArray().put(JSONObject().put("id", "native-mark")
            .put("anchor", JSONObject(created.get()!!))).toString() }
        compose.waitUntil(5_000) { locations.get()?.contains("attached") == true }
        awaitVisualFrame()
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-rendered-highlight", "annotation-reader", recordDensity = false,
            beforeCaptureFrame = ::awaitVisualFrame)
        assertEquals("Stored highlights must never wrap or rewrite article nodes", before,
            js("document.getElementById('eink-content').innerHTML"))
        assertReadingNavigationRestored()
        openSelection()
        compose.onNodeWithTag("reader_selection_done").performClick()
        compose.waitUntil(5_000) { !selecting.value }
        assertEquals(0, exits.get())
    }

    @Test fun nativeBackCancelsHeldCaptureBeforeItCanOpenANote() {
        startReader()
        longPressFirstWord()
        // Delay the actual WebView result after taking its real native selection.
        // Back must revoke the callback even when capture already succeeded in JS.
        js("""
            (function(){var original=InkReadAnnotations.captureSelection;
            InkReadAnnotations.captureSelection=function(g){var captured=original(g),until=Date.now()+1200;
            while(Date.now()<until){} return captured;};return true;})()
        """.trimIndent())
        clickNativeAction("Add note")
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
        // This evaluation is queued after the held result and is an explicit
        // completion barrier, not an arbitrary sleep or assumed idle state.
        assertEquals(true, js("true"))
        compose.waitForIdle()
        assertNull("Cancelled selection cannot create a late annotation", created.get())
        assertFalse(editing.value)
        assertEquals(0, exits.get())
    }

    @Test fun nativeHandlesAdjustDefaultSelectionWithoutPagingAtLargeReaderFont() {
        startReader(fontSize = 32)
        val clickDiagnostics = installHighlightCaptureObserver()
        val before = page.get()
        longPressFirstWord()
        assertTrue(nativeActionAvailable("Highlight"))
        closeNativeOverflow()
        val originalStart = textPoint("select-target", 0, 9, edge = "start")
        val originalEnd = textPoint("select-target", 0, 9, edge = "end")
        // Platform handles are separate PopupWindows, not children of WebView.
        // Match their small native root at each real selection endpoint instead
        // of a Chromium private/obfuscated class name or an app imitation.
        nativeHandleAt(originalStart).check(matches(isDisplayed()))
        nativeHandleAt(originalEnd).check(matches(isDisplayed()))
        dragSelectionHandle(originalEnd, textPoint("select-target", 10, 19, edge = "end"))
        compose.waitUntil(5_000) { js("window.getSelection().toString().trim()") == "Selection preserves" }
        assertTrue("Handle adjustment must keep native selection ownership", selecting.value)
        assertEquals("A handle drag must not turn pages", before, page.get())
        assertEquals("A handle drag must not trigger the article swipe", 0, nextArticles.get())
        assertEquals(0, previousArticles.get())
        // Chromium may rebuild ActionMode after classifying the expanded range.
        // Check after a committed frame, before volume input, so a transient
        // true host flag cannot hide selection lost during that replacement.
        assertNativeSelectionRetained("Selection preserves", "after handle release, before volume")
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        settle()
        assertEquals(before, page.get())
        assertNativeSelectionRetained("Selection preserves", "after volume down")
        volume(KeyEvent.KEYCODE_VOLUME_UP)
        settle()
        assertEquals(before, page.get())
        assertNativeSelectionRetained("Selection preserves", "after volume up")
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-native-adjusted-selection", "annotation-reader", recordDensity = false,
            beforeCaptureFrame = ::awaitVisualFrame)
        // A second, intentional gesture proves handles remain usable after their
        // first adjustment. It is not a retry of a failed drag.
        dragSelectionHandle(textPoint("select-target", 0, 19, edge = "end"), originalEnd)
        compose.waitUntil(5_000) { js("window.getSelection().toString().trim()") == "Selection" }
        val exact = js("window.getSelection().toString()") as String
        try {
            clickNativeAction("Highlight", clickDiagnostics)
            compose.waitUntil(5_000) { created.get() != null }
            assertEquals(exact, JSONObject(created.get()!!).getString("quote"))
            assertEquals(false, note.get())
        } finally {
            flushHighlightClickDiagnostics(clickDiagnostics, exact)
        }
        assertReadingNavigationRestored()
    }

    @Test fun outsideTapAndSwipeDismissOnlyThenRestoreNavigationAtSmallReaderFont() {
        startReader(fontSize = 12)
        val before = page.get()
        longPressFirstWord()
        nativeTap(.98f, .5f) // Right margin, away from selection/toolbar/Done.
        compose.waitUntil(5_000) { !selecting.value }
        assertEquals("The dismissal tap must not also turn the page", before, page.get())
        assertEquals(true, js("window.getSelection().isCollapsed"))
        assertNull(created.get())
        assertEquals(0, exits.get())
        assertEquals(0, nextArticles.get())
        assertEquals(0, previousArticles.get())
        assertReadingNavigationRestored()

        // A fresh selection tests a separate outside drag. Even if native text
        // handling collapses selection on DOWN, this gesture cannot become a
        // page turn or an article shortcut on UP.
        longPressFirstWord()
        val nextBefore = nextArticles.get()
        val previousBefore = previousArticles.get()
        nativeSwipe(.8f, .7f, .2f, .7f)
        settle()
        assertEquals("A swipe that starts in selection cannot turn pages", before, page.get())
        assertEquals(nextBefore, nextArticles.get())
        assertEquals(previousBefore, previousArticles.get())
        if (selecting.value) {
            // If WebView kept its selection during this outside drag, explicit
            // Back still ends it before the next deliberate navigation gesture.
            pressBack()
            compose.waitUntil(5_000) { !selecting.value }
        }
        assertEquals(0, exits.get())
        assertReadingNavigationRestored()
    }

    @Test fun linksAndMarginLongPressDoNotBecomePageTurnsOrExternalNavigation() {
        startReader()
        val before = page.get()
        val documentUrl = js("location.href")
        tapText("link-target", 0, 7)
        settle()
        assertEquals("A link tap must not be interpreted as a page tap", before, page.get())
        assertEquals("Existing external navigation blocking must be retained", documentUrl, js("location.href"))
        assertEquals(0, linkOpens.get())
        assertFalse(selecting.value)
        // The very next gesture must not inherit WebView's previous link hit.
        nativeTap(.8f, .5f)
        compose.waitUntil(5_000) { page.get() == before + 1 }
        nativeTap(.2f, .5f)
        compose.waitUntil(5_000) { page.get() == before }
        longPressFirstWord()
        tapText("link-target", 0, 7)
        compose.waitUntil(5_000) { !selecting.value }
        assertEquals("A link tap during selection is dismissal only", before, page.get())
        assertEquals(documentUrl, js("location.href"))
        assertEquals(0, linkOpens.get())
        assertNull(created.get())
        // Native dismissal and the renderer's range cleanup complete asynchronously.
        // Observe the actual cleared range before testing whether a blank hold restores it.
        compose.waitUntil(5_000) {
            js("window.getSelection().isCollapsed && window.getSelection().toString() === ''") == true
        }
        assertEquals("Selected-link dismissal must leave a collapsed, empty DOM range before a blank hold", true,
            js("window.getSelection().isCollapsed && window.getSelection().toString() === ''"))
        // Holding whitespace is not an invisible page-click shortcut.
        nativeTap(.98f, .5f, longPress = true)
        settle()
        assertEquals(before, page.get())
        assertFalse(selecting.value)
        assertEquals("A blank-margin hold must not resurrect the dismissed DOM range", true,
            js("window.getSelection().isCollapsed"))
        assertEquals("A blank-margin hold must not restore the old selected quote", "",
            js("window.getSelection().toString()"))
        assertEquals(0, nextArticles.get())
        assertEquals(0, previousArticles.get())
        assertReadingNavigationRestored()
    }

    @Test fun realBrowserCrossParagraphSelectionKeepsDisplayAndRawWhitespace() {
        startReader()
        openSelection()
        // Use real DOM Range/Selection objects in the device WebView to exercise
        // its layout-generated paragraph separators, in addition to gesture tests.
        val snapshot = JSONObject(js("""
            (function(){var first=document.getElementById('select-target').firstChild;
            var next=document.getElementById('paragraph-0').firstChild;
            var range=document.createRange();range.setStart(first,0);range.setEnd(next,12);
            var selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);
            return JSON.stringify({display:selection.toString(),raw:range.toString(),
                anchor:JSON.parse(InkReadAnnotations.captureSelection(window._inkreadGeneration))});})()
        """.trimIndent()) as String)
        val anchor = snapshot.getJSONObject("anchor")
        assertEquals(snapshot.getString("display"), anchor.getString("quote"))
        assertEquals(snapshot.getString("raw"), anchor.getString("rangeQuote"))
        assertTrue("The real selected paragraphs must retain browser line separators", anchor.getString("quote").contains('\n'))
        assertEquals(anchor.getString("rangeQuote").length, anchor.getInt("end") - anchor.getInt("start"))
    }

    private fun textPoint(id: String, start: Int, end: Int, edge: String = "center"): PointF {
        val geometry = JSONObject(js("""
            (function(){var target=document.getElementById(${JSONObject.quote(id)}),t=target.firstChild;
            var r=document.createRange();r.setStart(t,$start);r.setEnd(t,$end);
            var rects=r.getClientRects(),b=rects[${if (edge == "end") "rects.length-1" else "0"}];
            var x=(b.left+b.right)/2,y=(b.top+b.bottom)/2;
            return JSON.stringify({x:x,y:y,left:b.left,right:b.right,bottom:b.bottom,
                width:document.documentElement.getBoundingClientRect().width,height:innerHeight,
                word:r.toString(),hit:target.contains(document.elementFromPoint(x,y)),
                ready:document.readyState==='complete' && window._didFinishInitialPagination &&
                    InkReadAnnotations.documentId===window._inkreadGeneration});})()
        """.trimIndent()) as String)
        assertTrue("The live article fixture must be paginated: $geometry", geometry.getBoolean("ready"))
        assertTrue("The native gesture must target visible article text: $geometry", geometry.getBoolean("hit"))
        val result = AtomicReference<PointF>()
        instrumentation.runOnMainSync {
            val view = reader()
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            val scale = view.width / geometry.getDouble("width").toFloat()
            assertEquals("The DOM and native reader viewports must agree",
                view.height.toDouble(), geometry.getDouble("height") * scale, scale.toDouble() + 1)
            val x = geometry.getDouble(when (edge) { "start" -> "left"; "end" -> "right"; else -> "x" }).toFloat() * scale
            val y = geometry.getDouble(if (edge == "center") "y" else "bottom").toFloat() * scale
            assertTrue("Text must stay inside the reader: $geometry", x > 0 && x < view.width && y > 0 && y < view.height)
            result.set(PointF(origin[0] + x, origin[1] + y))
        }
        return result.get()
    }

    private fun closeNativeOverflow() {
        if (nativeOverflowOpen) {
            // Closing overflow is a toolbar action, not a Back press that could
            // destroy the entire ActionMode before the remaining menu assertions.
            onView(allOf(withContentDescription("Close overflow"), isDisplayed()))
                .inRoot(nativeToolbarRoot()).perform(click())
            nativeOverflowOpen = false
        }
    }

    private fun nativeHandleAt(endpoint: PointF) = onView(isRoot()).inRoot(withDecorView(
        object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("native selection-handle popup at ").appendValue(endpoint)
            }
            override fun matchesSafely(view: View): Boolean {
                val density = view.resources.displayMetrics.density
                if (!view.isShown || view.width !in 1..(64 * density).toInt() ||
                    view.height !in 1..(96 * density).toInt()) return false
                val origin = IntArray(2)
                view.getLocationOnScreen(origin)
                return abs(origin[0] + view.width / 2f - endpoint.x) <= 32 * density &&
                    abs(origin[1] - endpoint.y) <= 32 * density
            }
        },
    ))

    private fun dragSelectionHandle(from: PointF, to: PointF) {
        nativeHandleAt(from).perform(realTimeDrag(
            start = { view -> screenPoint(view, .5f, .5f) },
            end = { _, start -> PointF(to.x + start.x - from.x, to.y + start.y - from.y) },
        ))
    }

    private fun screenPoint(view: View, x: Float, y: Float): PointF {
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        return PointF(origin[0] + view.width * x, origin[1] + view.height * y)
    }

    private fun tapText(id: String, start: Int, end: Int) {
        val point = textPoint(id, start, end)
        onView(isAssignableFrom(EInkAnnotationWebView::class.java)).perform(GeneralClickAction(
            Tap.SINGLE, { floatArrayOf(point.x, point.y) }, Press.FINGER,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        ))
    }

    private fun nativeTap(x: Float, y: Float, longPress: Boolean = false) {
        onView(isAssignableFrom(EInkAnnotationWebView::class.java)).perform(GeneralClickAction(
            if (longPress) Tap.LONG else Tap.SINGLE,
            { view -> screenPoint(view, x, y).let { floatArrayOf(it.x, it.y) } },
            Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0,
        ))
    }

    private fun nativeSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        onView(isAssignableFrom(EInkAnnotationWebView::class.java)).perform(realTimeDrag(
            start = { view -> screenPoint(view, fromX, fromY) },
            end = { view, _ -> screenPoint(view, toX, toY) },
        ))
    }

    // Espresso injects at the window boundary, including popup windows. Every
    // timestamp comes from the real Android clock; delays service the main loop.
    // No Compose-clock-only events, direct dispatchTouchEvent, or retry gestures.
    private fun realTimeDrag(start: (View) -> PointF, end: (View, PointF) -> PointF) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription() = "one native DOWN/MOVE/UP drag with real elapsed eventTime"
        override fun perform(uiController: UiController, view: View) {
            val from = start(view)
            val to = end(view, from)
            val downAt = SystemClock.uptimeMillis()
            val eventTimes = mutableListOf<Long>()
            fun send(action: Int, point: PointF) {
                val time = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(downAt, time, action, point.x, point.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue("Native touch injection must succeed", uiController.injectMotionEvent(event)) }
                finally { event.recycle() }
                eventTimes += time
            }
            send(MotionEvent.ACTION_DOWN, from)
            var released = false
            var latest = from
            try {
                for (step in 1..20) {
                    uiController.loopMainThreadForAtLeast(16)
                    val progress = step / 20f
                    latest = PointF(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)
                    send(MotionEvent.ACTION_MOVE, latest)
                }
                uiController.loopMainThreadForAtLeast(16)
                send(MotionEvent.ACTION_UP, to)
                released = true
            } finally {
                if (!released) send(MotionEvent.ACTION_CANCEL, latest)
            }
            assertTrue("Drag eventTime must advance with actual Android uptime", eventTimes.last() - downAt >= 20 * 16)
            assertTrue(eventTimes.zipWithNext().all { (previous, current) -> current >= previous })
            Log.i("InkReadAnnotationTest", "Native drag: from=$from to=$to eventTimes=$eventTimes")
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun assertReadingNavigationRestored() {
        compose.waitUntil(5_000) { !selecting.value && !editing.value }
        compose.onNodeWithTag("reader_controls_toggle").assertIsDisplayed()
        val before = page.get()
        nativeTap(.8f, .5f)
        compose.waitUntil(5_000) { page.get() == before + 1 }
        nativeTap(.2f, .5f)
        compose.waitUntil(5_000) { page.get() == before }
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(5_000) { page.get() == before + 1 }
        volume(KeyEvent.KEYCODE_VOLUME_UP)
        compose.waitUntil(5_000) { page.get() == before }
        val nextBefore = nextArticles.get()
        val previousBefore = previousArticles.get()
        nativeSwipe(.8f, .5f, .2f, .5f)
        compose.waitUntil(5_000) { nextArticles.get() == nextBefore + 1 }
        nativeSwipe(.2f, .5f, .8f, .5f)
        compose.waitUntil(5_000) { previousArticles.get() == previousBefore + 1 }
        assertEquals("Article shortcuts must not also turn pages", before, page.get())
    }

    // Floating ActionMode uses a nonfocusable PopupWindow, which Espresso's
    // default (focused activity) root deliberately excludes. Target the actual
    // platform toolbar, not the selection-handle popups or an app lookalike.
    private fun nativeToolbarRoot() = withDecorView(
        hasDescendant(withTagValue(equalTo<Any>("floating_toolbar"))),
    )
    private fun nativeAction(label: String) = onView(allOf(withText(label), isDisplayed()))
        .inRoot(nativeToolbarRoot())

    private fun nativeActionAvailable(label: String): Boolean {
        // Selection exists before Chromium's asynchronous text classification
        // shows the toolbar. Espresso's root picker waits for this popup to exist.
        onView(withTagValue(equalTo<Any>("floating_toolbar")))
            .inRoot(nativeToolbarRoot()).check(matches(isDisplayed()))
        try {
            nativeAction(label).check(matches(isDisplayed()))
        } catch (_: NoMatchingViewException) {
            // The floating toolbar's overflow is not the activity/action-bar
            // overflow searched by openContextualActionModeOverflowMenu().
            onView(allOf(withContentDescription("More options"), isDisplayed()))
                .inRoot(nativeToolbarRoot()).perform(click())
            nativeOverflowOpen = true
            // The platform overflow is a real ListView. At narrow widths / large
            // font scales its lower rows are partial or not bound at all. Let
            // Espresso scroll the native adapter before the normal visible click.
            onData(object : TypeSafeMatcher<MenuItem>() {
                override fun matchesSafely(item: MenuItem) = item.title.toString() == label
                override fun describeTo(description: Description) {
                    description.appendText("native selection menu item titled ").appendValue(label)
                }
            }).inRoot(nativeToolbarRoot())
                .inAdapterView(allOf(isAssignableFrom(ListView::class.java), isDisplayed()))
                .check(matches(isCompletelyDisplayed()))
        }
        return true
    }
    private fun assertNativeAnnotationActionsAbsent() {
        try {
            onView(allOf(withContentDescription("More options"), isDisplayed()))
                .inRoot(nativeToolbarRoot()).perform(click())
            nativeOverflowOpen = true
        } catch (_: NoMatchingViewException) {
            // No overflow is a valid native layout. The main toolbar still must
            // exist, and the full menu inspection below must still run.
        }
        onView(withTagValue(equalTo<Any>("floating_toolbar")))
            .inRoot(nativeToolbarRoot()).check { view, missing ->
                if (missing != null) throw missing
                val labels = mutableSetOf<String>()
                fun inspect(node: View) {
                    if (node is TextView) labels += node.text.toString()
                    if (node is ListView) {
                        // Inspect every adapter row, not only currently visible
                        // ones: a hidden/unbound action must fail this assertion.
                        val adapter = requireNotNull(node.adapter)
                        for (index in 0 until adapter.count) {
                            val item = adapter.getItem(index)
                            assertTrue("The native overflow adapter must expose MenuItem rows", item is MenuItem)
                            labels += (item as MenuItem).title.toString()
                        }
                    }
                    if (node is ViewGroup) for (index in 0 until node.childCount) inspect(node.getChildAt(index))
                }
                inspect(requireNotNull(view))
                assertFalse("Unavailable Highlight must not be offered anywhere in the native menu: $labels", "Highlight" in labels)
                assertFalse("Unavailable Add note must not be offered anywhere in the native menu: $labels", "Add note" in labels)
            }
        closeNativeOverflow()
    }

    private fun clickNativeAction(label: String, diagnostics: HighlightClickDiagnostics? = null) {
        nativeActionAvailable(label)
        nativeAction(label).perform(if (diagnostics == null) click() else traceOneNativeClick(diagnostics))
        nativeOverflowOpen = false
    }

    // NONSHIPPING DIAGNOSTIC: used only by the existing handle test above.
    // Collect in memory at injection time; logging and JS reads happen afterward
    // so they cannot intentionally wait for or repair the native toolbar.
    private class HighlightClickDiagnostics {
        val events = mutableListOf<String>() // All writes and copies occur on the main thread.
        var restoreActionObserver: (() -> Unit)? = null
        fun record(event: String) { events += "uptime=${SystemClock.uptimeMillis()} $event" }
    }

    private fun installHighlightCaptureObserver(): HighlightClickDiagnostics {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        diagnosticAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        // Forward exactly once with the same receiver/arguments/result. This
        // observes the atomic JS return, not delivery of Android's JS callback.
        // No DOM range, menu, focus, capture ticket, or selection state is changed.
        assertEquals(true, js("""
            (function(){
                var original=InkReadAnnotations.captureSelection;
                window.__inkreadHighlightCapture={calls:0,returns:[],errors:[]};
                InkReadAnnotations.captureSelection=function(){
                    var trace=window.__inkreadHighlightCapture;trace.calls++;
                    try {var result=original.apply(this,arguments);trace.returns.push(result);return result;}
                    catch(error){trace.errors.push(String(error));throw error;}
                };
                return true;
            })()
        """.trimIndent()))
        return HighlightClickDiagnostics()
    }

    private fun traceOneNativeClick(diagnostics: HighlightClickDiagnostics): ViewAction {
        val originalClick = click()
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> = originalClick.constraints
            override fun getDescription() = "one unchanged native click with passive in-memory target and delivery observations"
            override fun perform(uiController: UiController, view: View) {
                val webView = reader() as EInkAnnotationWebView
                val originalAction = webView.onAnnotationAction
                webView.onAnnotationAction = { addNote, mode ->
                    diagnostics.record("native_annotation_action addNote=$addNote mode=${System.identityHashCode(mode)}")
                    originalAction(addNote, mode)
                    diagnostics.record("native_annotation_action_return addNote=$addNote")
                }
                diagnostics.restoreActionObserver = { webView.onAnnotationAction = originalAction }
                webView.setOnTouchListener { _, event ->
                    diagnostics.record("webview_touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                        "eventTime=${event.eventTime} downTime=${event.downTime} x=${event.x} y=${event.y} " +
                        "rawX=${event.rawX} rawY=${event.rawY}")
                    false
                }
                val observingController = object : UiController by uiController {
                    override fun injectMotionEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            recordNativeClickHierarchy(view, diagnostics)
                            recordNativeTouchableRegion(view, event.rawX, event.rawY, diagnostics)
                        }
                        diagnostics.record("inject_touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                            "eventTime=${event.eventTime} downTime=${event.downTime} x=${event.x} y=${event.y} " +
                            "rawX=${event.rawX} rawY=${event.rawY}")
                        return uiController.injectMotionEvent(event)
                    }
                }
                try {
                    // Delegate the original Espresso click, without retrying,
                    // replacing its coordinates, sleeping, or calling performClick.
                    originalClick.perform(observingController, view)
                } finally {
                    webView.setOnTouchListener(null)
                }
            }
        }
    }

    private fun recordNativeClickHierarchy(target: View, diagnostics: HighlightClickDiagnostics) {
        var current: View? = target
        var depth = 0
        while (current != null) {
            val node = current
            val rect = Rect()
            val visibleRect = node.getGlobalVisibleRect(rect)
            val position = IntArray(2)
            node.getLocationOnScreen(position)
            val params = node.layoutParams as? WindowManager.LayoutParams
            diagnostics.record("target_ancestor depth=$depth id=${System.identityHashCode(node)} " +
                "class=${node.javaClass.name} text=${(node as? TextView)?.text} " +
                "alpha=${node.alpha} shown=${node.isShown} visibility=${node.visibility} " +
                "attached=${node.isAttachedToWindow} laidOut=${node.isLaidOut} enabled=${node.isEnabled} " +
                "clickable=${node.isClickable} windowFocus=${node.hasWindowFocus()} " +
                "rectVisible=$visibleRect rect=${rect.toShortString()} " +
                "screen=${position.contentToString()} size=${node.width}x${node.height} " +
                "windowToken=${System.identityHashCode(node.windowToken)} " +
                "windowFlags=${params?.flags} windowType=${params?.type} " +
                "animationStarted=${node.animation?.hasStarted()} animationEnded=${node.animation?.hasEnded()}")
            current = node.parent as? View
            depth++
        }
    }

    private fun recordNativeTouchableRegion(target: View, x: Float, y: Float, diagnostics: HighlightClickDiagnostics) {
        val started = SystemClock.elapsedRealtimeNanos()
        runCatching {
            // Public APIs only. getAccessibilityWindowId() on View is hidden;
            // the locally created node exposes the same public window ID.
            val targetNode = target.createAccessibilityNodeInfo()
            val windowId = targetNode.windowId
            targetNode.recycle()
            val windows = instrumentation.uiAutomation.windows
            try {
                diagnostics.record("touchable_window_query targetWindow=$windowId count=${windows.size}")
                for (window in windows) {
                    val region = Region()
                    window.getRegionInScreen(region)
                    diagnostics.record("touchable_window id=${window.id} target=${window.id == windowId} " +
                        "layer=${window.layer} type=${window.type} active=${window.isActive} " +
                        "focused=${window.isFocused} region=$region containsClick=${region.contains(x.toInt(), y.toInt())}")
                }
            } finally {
                windows.forEach { it.recycle() }
            }
        }.onFailure { diagnostics.record("touchable_window_observation_error=${it.javaClass.name}:${it.message}") }
        diagnostics.record("touchable_window_observation_elapsedNs=${SystemClock.elapsedRealtimeNanos() - started}")
    }

    private fun flushHighlightClickDiagnostics(diagnostics: HighlightClickDiagnostics, expected: String) {
        // A failed observation must not replace the original exact-quote assertion
        // or creation timeout. All contents are this test's synthetic local fixture.
        runCatching {
            val events = AtomicReference<List<String>>()
            instrumentation.runOnMainSync {
                diagnostics.restoreActionObserver?.invoke()
                events.set(diagnostics.events.toList())
            }
            events.get().forEach { Log.i("InkReadHighlightClick", it) }
            Log.i("InkReadHighlightClick", "expected_quote=${JSONObject.quote(expected)} " +
                "host_created=${created.get()} host_selecting=${selecting.value}")
            val snapshot = js("""
                (function(){var s=window.getSelection();return JSON.stringify({
                    capture:window.__inkreadHighlightCapture,quote:s.toString(),
                    collapsed:s.isCollapsed,rangeCount:s.rangeCount});})()
            """.trimIndent()).toString()
            snapshot.chunked(3000).forEachIndexed { index, part ->
                Log.i("InkReadHighlightClick", "post_result_snapshot part=$index $part")
            }
        }.onFailure { Log.w("InkReadHighlightClick", "Diagnostic observation failed without replacing test result", it) }
    }
    private fun volume(code: Int) = instrumentation.runOnMainSync {
        assertTrue(dispatchEInkVolumeKey(true, code, KeyEvent.ACTION_DOWN, 0))
        assertTrue(dispatchEInkVolumeKey(true, code, KeyEvent.ACTION_UP, 0))
    }
    private fun prepareNativeCopyClipboard(sentinel: String) {
        instrumentation.runOnMainSync {
            val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Annotation test fixture", sentinel))
        }
        assertEquals(sentinel, clipboardText())
    }
    private fun clipboardText(): String? {
        val result = AtomicReference<String?>()
        instrumentation.runOnMainSync {
            val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            result.set(clipboard.primaryClip?.getItemAt(0)?.coerceToText(instrumentation.targetContext)?.toString())
        }
        return result.get()
    }
    private fun assertNativeCopyResult(expected: String) {
        // The native clipboard write can complete after ActionMode is destroyed.
        // Wait for that result, never repeat the gesture or accept old clipboard text.
        compose.waitUntil(5_000) { clipboardText() == expected }
        assertEquals("Copy must remain the real system action", expected, clipboardText())
    }
    private fun assertNativeSelectionRetained(expected: String, phase: String) {
        settle()
        val quote = js("window.getSelection().toString()")
        val collapsed = js("window.getSelection().isCollapsed")
        Log.i("InkReadAnnotationTest", "Selection retention $phase: selecting=${selecting.value} collapsed=$collapsed quote=$quote")
        assertTrue("Native selection must retain host ownership $phase", selecting.value)
        assertEquals("Native selection must remain noncollapsed $phase", false, collapsed)
        assertEquals("Native selection must retain its exact adjusted quote $phase", expected, quote)
        nativeHandleAt(textPoint("select-target", 0, expected.length, edge = "start"))
            .check(matches(isDisplayed()))
        nativeHandleAt(textPoint("select-target", 0, expected.length, edge = "end"))
            .check(matches(isDisplayed()))
    }
    private fun settle() { compose.waitForIdle(); awaitVisualFrame() }
    private fun awaitVisualFrame() {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            reader().postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { latch.countDown() }
            })
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
    }
    private fun js(script: String): Any? {
        val result = AtomicReference<Any?>(); val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { reader().evaluateJavascript(script) {
            result.set(JSONTokener(it).nextValue()); latch.countDown()
        } }
        assertTrue(latch.await(10, TimeUnit.SECONDS));return result.get()
    }
    private fun reader(): WebView {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
        return requireNotNull(find(activity.window.decorView))
    }
}
