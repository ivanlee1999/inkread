package me.ash.reader.ui.page.home.reading

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Region
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import android.webkit.WebView
import android.widget.ListView
import android.widget.TextView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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
import androidx.test.espresso.action.GeneralLocation
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
import java.io.File
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
import me.ash.reader.ui.page.adaptive.ReaderContentPresentationGate
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
    private val tappedAnnotation = AtomicReference<String?>()
    private val resizeRequest = mutableStateOf<Pair<String, Long>?>(null)
    private val resizeRestored = AtomicReference<Pair<String, Boolean>?>()
    private val resized = AtomicReference<Pair<String, String>?>()
    private val automatic = mutableStateOf(false)
    private val annotationStatus = mutableStateOf<String?>(null)
    private val automaticCount = AtomicInteger()
    private val automaticAnchor = AtomicReference<String?>()
    private val undoAvailable = mutableStateOf(false)
    private val undoCount = AtomicInteger()
    private val incomingContent = mutableStateOf<String?>(null)
    private val contentPresentation = ReaderContentPresentationGate<String, String> { it }
    private val nextDocumentInteraction = AtomicReference<(() -> Unit)?>(null)
    private val testFormatting = mutableStateOf(ReaderFormatting.Default)
    private var nativeOverflowOpen = false
    private val endpointAvailableLeading = mutableMapOf<PointF, Float>()
    private var originalAccessibilityFlags: Int? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun prepare() { VolumeKeyEventBus.resetForTest() }
    @After fun cleanup() {
        try {
            originalAccessibilityFlags?.let { flags ->
                val automation = instrumentation.uiAutomation
                val info = automation.serviceInfo
                info.flags = flags
                automation.serviceInfo = info
                originalAccessibilityFlags = null
            }
        } finally {
            VolumeKeyEventBus.resetForTest()
        }
    }

    private fun startReader(fontSize: Int = ReaderFormatting.Default.fontSize, annotationsAvailable: Boolean = true,
        authoredTightLineHeight: Boolean = false) {
        testFormatting.value = ReaderFormatting(fontSize = fontSize)
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalReaderFormatting provides testFormatting.value) {
                AppTheme(useDarkTheme = false) {
                    val initialContent = buildString {
                        if (authoredTightLineHeight) {
                            // Author-important declarations cannot bypass the measured selection floor.
                            append("<p style='line-height:1!important'><span id='select-target' style='line-height:.75!important'>" +
                                "Selection preserves exact text, 中文 and emoji 😀.</span></p>")
                        } else {
                            append("<p id='select-target'>Selection preserves exact text, 中文 and emoji 😀.</p>")
                        }
                        append("<p><a id='link-target' href='https://example.invalid/annotation-fixture'>Fixture link</a></p>")
                        repeat(140) { append("<p id='paragraph-$it'>Paragraph $it. Long article text exercises native paging and annotation ownership.</p>") }
                    }
                    EInkPaginatedContent(modifier = Modifier.testTag("annotation-reader"),
                        articleId = "annotation-fixture", title = "Annotation fixture", feedName = "Local fixture",
                        publishedDate = Date(0),
                        content = contentPresentation.present("annotation-fixture", incomingContent.value ?: initialContent),
                        onDocumentInteractionStarted = {
                            contentPresentation.onInteractionStarted()
                            nextDocumentInteraction.getAndSet(null)?.invoke()
                        },
                        selectionMode = selecting.value, onSelectionModeChange = { selecting.value = it },
                        annotationEditing = editing.value, annotationsJson = annotationJson.value,
                        onCreateAnnotation = if (annotationsAvailable) {
                            { json, addNote -> created.set(json); note.set(addNote); editing.value = addNote }
                        } else null,
                        onAnnotationLocationsChanged = { locations.set(it) }, onAnnotationsClick = {},
                        onAnnotationClick = { tappedAnnotation.set(it) },
                        annotationResizeRequest = resizeRequest.value,
                        onAnnotationResizeResult = { id, attached -> resizeRestored.set(id to attached) },
                        onResizeSelection = { id, json -> resized.set(id to json) },
                        autoHighlightEnabled = automatic.value,
                        annotationStatus = annotationStatus.value,
                        onAutoCreateAnnotation = { json ->
                            automaticAnchor.set(json)
                            automaticCount.incrementAndGet()
                            undoAvailable.value = true
                            annotationStatus.value = "Saved locally · Readwise pending"
                        },
                        onUndoAutoHighlight = if (undoAvailable.value) {
                            { undoCount.incrementAndGet(); undoAvailable.value = false; annotationStatus.value = "Highlight removed" }
                        } else null,
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

    private fun longPressFirstWord(awaitSelection: Boolean = true, beforeRelease: (() -> Unit)? = null): NativeTouchTrace {
        // This Range is read-only geometry. Only window-dispatched native touch
        // can create the Selection/ActionMode. No addRange or performLongClick.
        val point = textPoint("select-target", 0, 9)
        val downAt = AtomicLong(-1)
        val heldFor = AtomicLong(-1)
        val touchEvents = StringBuffer()
        instrumentation.runOnMainSync {
            reader().setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) downAt.set(event.eventTime)
                if (event.actionMasked == MotionEvent.ACTION_UP) beforeRelease?.invoke()
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

    @Test fun incomingArticleKeepsNativeSelectionEvenWhenAnnotationsAreUnavailable() {
        startReader(annotationsAvailable = false)
        val generation = js("window._inkreadGeneration")
        longPressFirstWord()
        val exact = js("window.getSelection().toString()") as String
        compose.runOnIdle { incomingContent.value = "<p id='replacement-body'>Newly extracted article.</p>" }
        assertNativeSelectionRetained(exact, "after an incoming full article")
        assertEquals(generation, js("window._inkreadGeneration"))
        assertEquals(false, js("!!document.getElementById('replacement-body')"))
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
        settle()
        assertEquals("Ending selection is not permission to replace its document", generation, js("window._inkreadGeneration"))
        assertEquals(false, js("!!document.getElementById('replacement-body')"))
    }

    @Test fun incomingArticleAtNativeCapturePreservesTheNoteHandoff() {
        startReader()
        val generation = js("window._inkreadGeneration")
        longPressFirstWord()
        val exact = js("window.getSelection().toString()") as String
        // Complete a background fetch on the synchronous capture-entry callback. The real
        // Chromium selection and capture still run normally, without JS Selection mocks.
        nextDocumentInteraction.set {
            incomingContent.value = "<p id='replacement-body'>Article fetched while capture starts.</p>"
        }
        clickNativeAction("Add note")
        compose.waitUntil(5_000) { created.get() != null && editing.value }
        assertNull("The native interaction hook must consume the queued fetch", nextDocumentInteraction.get())
        assertEquals(exact, JSONObject(created.get()!!).getString("quote"))
        assertEquals(true, note.get())
        assertEquals(generation, js("window._inkreadGeneration"))
        compose.runOnIdle { incomingContent.value = "<p id='replacement-body'>Another result while editing the note.</p>" }
        settle()
        assertTrue("A new body cannot dismiss the accepted note editor", editing.value)
        assertEquals(exact, JSONObject(created.get()!!).getString("quote"))
        assertEquals(generation, js("window._inkreadGeneration"))
        assertEquals(false, js("!!document.getElementById('replacement-body')"))
    }

    @Test fun mixedScriptHighlightRetainsExactAnchorAcrossIndependentSizesAndResize() {
        startReader(authoredTightLineHeight = true)
        openSelection()
        val original = JSONObject(js("""(function(){
            var model=InkReadScriptFonts.install(document.getElementById('eink-content'));
            var n=model.annotationNodes.find(function(n){return n.parentElement.id==='select-target';});
            var range=model.range(n,0,n.length), selection=getSelection();
            selection.removeAllRanges();selection.addRange(range);
            return InkReadAnnotations.captureSelection(window._inkreadGeneration);
        })()""") as String)
        assertTrue(original.getString("rangeQuote").contains("中文"))
        val html = js("document.getElementById('eink-content').innerHTML")
        compose.runOnIdle { annotationJson.value = JSONArray().put(JSONObject().put("id", "mixed-script")
            .put("anchor", original)).toString() }
        compose.waitUntil(5_000) { locations.get()?.contains("attached") == true }
        compose.onNodeWithTag("reader_selection_done").performClick()
        compose.waitUntil(5_000) { !selecting.value }
        listOf(12 to 48, 48 to 12, 20 to 24).forEachIndexed { index, (english, chinese) ->
            compose.runOnIdle { testFormatting.value = ReaderFormatting(fontSize = english, chineseFontSize = chinese) }
            compose.waitUntil(10_000) {
                js("Math.abs(parseFloat(getComputedStyle(document.querySelector('#select-target .eink-script-cjk')).fontSize) - " +
                    (chinese * instrumentation.targetContext.resources.configuration.fontScale) + ") < .1") == true
            }
            assertEquals("Formatting must not rewrite the indexed article", html,
                js("document.getElementById('eink-content').innerHTML"))
            resizeRestored.set(null)
            compose.runOnIdle { resizeRequest.value = "mixed-script" to (index + 1L) }
            compose.waitUntil(5_000) { resizeRestored.get() == ("mixed-script" to true) && selecting.value }
            val restored = JSONObject(js("InkReadAnnotations.captureSelection(window._inkreadGeneration)") as String)
            listOf("documentFingerprint", "rangeQuote", "prefix", "suffix", "start", "end",
                "startNode", "endNode", "startOffset", "endOffset").forEach { key ->
                assertEquals("Independent sizing must preserve $key", original.get(key), restored.get(key))
            }
            pressBack()
            compose.waitUntil(5_000) { !selecting.value }
            assertNull("Reflow/cancel cannot commit a resized highlight", resized.get())
            // Exercise real Chromium handles with unequal rendered sizes too,
            // while the existing mixed-script annotation remains attached.
            longPressFirstWord()
            closeNativeOverflow()
            dragSelectionHandle(textPoint("select-target", 0, 9, "end"),
                textPoint("select-target", 10, 19, "end"))
            awaitHandleSelection("Selection preserves", "independent-$english-$chinese")
            assertNativeSelectionRetained("Selection preserves", "independent sizes $english/$chinese")
            if (english == 20 && chinese == 24) {
                // This moderate pair keeps the CJK endpoint visible on the
                // narrowest profile while exercising a real cross-script drag.
                dragSelectionHandle(textPoint("select-target", 0, 19, "end"),
                    textPoint("select-target", 32, 34, "end"))
                val expected = "Selection preserves exact text, 中文"
                awaitHandleSelection(expected, "independent-cross-script")
                assertNativeSelectionRetained(expected, "native drag across unequal script sizes")
                val captured = JSONObject(js("InkReadAnnotations.captureSelection(window._inkreadGeneration)") as String)
                assertEquals(original.getString("documentFingerprint"), captured.getString("documentFingerprint"))
                assertEquals(original.getInt("start"), captured.getInt("start"))
                assertEquals(original.getInt("startNode"), captured.getInt("startNode"))
                assertEquals(original.getInt("endNode"), captured.getInt("endNode"))
                assertEquals(original.getInt("startOffset"), captured.getInt("startOffset"))
                assertEquals(original.getInt("startOffset") + captured.getString("rangeQuote").length,
                    captured.getInt("endOffset"))
                assertEquals(expected, captured.getString("rangeQuote").trim())
                assertEquals(original.getString("rangeQuote").substring(0, captured.getInt("end") - original.getInt("start")),
                    captured.getString("rangeQuote"))
            }
            pressBack()
            compose.waitUntil(5_000) { !selecting.value }
            assertNull("Unequal-size handle gestures cannot overwrite the existing annotation", resized.get())
        }
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
        awaitHandleSelection("Selection preserves", "default-selection")
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
        clickNativeAction("Highlight")
        compose.waitUntil(5_000) { created.get() != null }
        assertEquals(exact, JSONObject(created.get()!!).getString("quote"))
        assertEquals(false, note.get())
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

    private fun saveFirstWordHighlight(id: String = "saved-highlight") {
        longPressFirstWord()
        clickNativeAction("Highlight")
        compose.waitUntil(5_000) { created.get() != null && !selecting.value }
        val json = JSONArray().put(JSONObject().put("id", id).put("anchor", JSONObject(created.get()!!))).toString()
        compose.runOnIdle { annotationJson.value = json }
        compose.waitUntil(5_000) { locations.get()?.contains("attached") == true }
        awaitVisualFrame()
    }

    private fun requestResize(id: String, serial: Long) {
        resizeRestored.set(null)
        compose.runOnIdle { resizeRequest.value = id to serial }
        compose.waitUntil(5_000) { resizeRestored.get() == (id to true) && selecting.value }
        assertEquals("Selection", js("window.getSelection().toString()"))
        awaitVisualFrame()
    }

    @Test fun defaultHighlightTapTargetsExistingRecordWithoutPagingAndLinkStillWins() {
        startReader()
        saveFirstWordHighlight()
        val before = page.get()
        tapText("select-target", 0, 9)
        compose.waitUntil(5_000) { tappedAnnotation.get() == "saved-highlight" }
        assertEquals(before, page.get())
        assertFalse(selecting.value)
        tappedAnnotation.set(null)
        tapText("link-target", 0, 7)
        settle()
        assertNull(tappedAnnotation.get())
        assertEquals(before, page.get())
        assertEquals(0, linkOpens.get())
    }

    @Test fun resizeRestoresExactRangeAndRealHandlesOnlySaveOnExplicitCommit() {
        startReader(fontSize = 32)
        saveFirstWordHighlight()
        val before = page.get()
        val viewport = js("JSON.stringify([innerWidth,innerHeight])")
        requestResize("saved-highlight", 1)
        // Restoring a DOM range does not manufacture native handles. One real
        // tap inside it asks Chromium to reveal them without changing its text.
        tapText("select-target", 0, 9)
        awaitVisualFrame()
        nativeHandleAt(textPoint("select-target", 0, 9, "start")).check(matches(isDisplayed()))
        nativeHandleAt(textPoint("select-target", 0, 9, "end")).check(matches(isDisplayed()))
        assertTrue(nativeActionAvailable("Save highlight")); closeNativeOverflow()
        assertTrue(nativeActionAvailable("Cancel")); closeNativeOverflow()
        assertTrue(nativeActionAvailable("Copy")); closeNativeOverflow()
        dragSelectionHandle(textPoint("select-target", 0, 9, "end"), textPoint("select-target", 10, 19, "end"))
        awaitHandleSelection("Selection preserves", "resize-explicit-save")
        assertNull("Handle motion/release cannot persist a resize", resized.get())
        assertEquals(0, automaticCount.get())
        assertTrue(nativeActionAvailable("Save highlight"))
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-resize-selection", "annotation-reader", recordDensity = false,
            beforeCaptureFrame = ::awaitVisualFrame)
        closeNativeOverflow()
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        settle()
        assertEquals(before, page.get())
        assertEquals(viewport, js("JSON.stringify([innerWidth,innerHeight])"))
        clickNativeAction("Save highlight")
        compose.waitUntil(5_000) { resized.get() != null && !selecting.value }
        assertEquals("saved-highlight", resized.get()!!.first)
        assertEquals("Selection preserves", JSONObject(resized.get()!!.second).getString("quote").trim())
        assertEquals("Selection", JSONObject(created.get()!!).getString("quote"))
        assertEquals(0, exits.get())
    }

    @Test fun incomingArticleKeepsRestoredResizeBeforeItsNativeToolbarExists() {
        startReader()
        saveFirstWordHighlight()
        // Do not let the previous creation's pin mask a missing resize-start hook.
        compose.runOnIdle { assertTrue(contentPresentation.onExplicitSourceChoice { true }) }
        val generation = js("window._inkreadGeneration")
        requestResize("saved-highlight", 1)
        compose.runOnIdle { incomingContent.value = "<p id='replacement-body'>New full article during resize.</p>" }
        settle()
        assertTrue(selecting.value)
        assertEquals("Selection", (js("window.getSelection().toString()") as String).trim())
        assertEquals(generation, js("window._inkreadGeneration"))
        assertEquals(false, js("!!document.getElementById('replacement-body')"))
        assertNull("Content arrival cannot commit a resize", resized.get())
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
        settle()
        assertEquals(generation, js("window._inkreadGeneration"))
        assertNull(resized.get())
    }

    @Test fun resizeBackBeforeToolbarAndCancelAfterHandleDragNeverSave() {
        startReader(fontSize = 32)
        saveFirstWordHighlight()
        val before = page.get()
        requestResize("saved-highlight", 1)
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        settle()
        assertEquals(before, page.get())
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
        assertEquals(0, exits.get())
        assertNull(resized.get())
        assertEquals(true, js("window.getSelection().isCollapsed"))
        requestResize("saved-highlight", 2)
        tapText("select-target", 0, 9)
        awaitVisualFrame()
        dragSelectionHandle(textPoint("select-target", 0, 9, "end"), textPoint("select-target", 10, 19, "end"))
        awaitHandleSelection("Selection preserves", "resize-cancel")
        clickNativeAction("Cancel")
        compose.waitUntil(5_000) { !selecting.value }
        settle()
        assertNull(resized.get())
        assertEquals(0, automaticCount.get())
        assertEquals(before, page.get())
        assertEquals(0, exits.get())
    }

    @Test fun resizeCopyPreservesExactClipboardAndDoesNotSaveRevision() {
        startReader()
        saveFirstWordHighlight()
        prepareNativeCopyClipboard("Before resize Copy")
        requestResize("saved-highlight", 1)
        tapText("select-target", 0, 9)
        awaitVisualFrame()
        clickNativeAction("Copy")
        compose.waitUntil(5_000) { !selecting.value }
        assertNativeCopyResult("Selection")
        assertNull(resized.get())
        assertEquals(0, automaticCount.get())
        assertEquals(0, exits.get())
    }

    @Test fun resizeBackRevokesAlreadyCapturedButHeldSaveResult() {
        startReader()
        saveFirstWordHighlight()
        requestResize("saved-highlight", 1)
        tapText("select-target", 0, 9)
        awaitVisualFrame()
        js("""
            (function(){var original=InkReadAnnotations.captureResize;
            InkReadAnnotations.captureResize=function(id,token,g){var captured=original(id,token,g),until=Date.now()+1200;
            while(Date.now()<until){} return captured;};return true;})()
        """.trimIndent())
        clickNativeAction("Save highlight")
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
        assertEquals(true, js("true")) // Queued behind the held result, not a sleep.
        compose.waitForIdle()
        assertNull("Back revokes the immutable resize snapshot before persistence", resized.get())
        assertEquals(0, automaticCount.get())
        assertEquals(0, exits.get())
    }

    @Test fun automaticHighlightRequiresReleasedGestureDefaultsOffAndKeepsNativeUndo() {
        startReader(fontSize = 32)
        longPressFirstWord()
        settle()
        assertEquals("Auto-highlight is off by default", 0, automaticCount.get())
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
        compose.runOnIdle { automatic.value = true }
        settle()
        val before = page.get()
        var sawRelease = false
        longPressFirstWord(beforeRelease = {
            assertEquals("A held or intermediate selection must not save", 0, automaticCount.get())
            sawRelease = true
        })
        assertTrue(sawRelease)
        compose.waitUntil(5_000) { automaticCount.get() == 1 }
        assertEquals("Selection", JSONObject(automaticAnchor.get()!!).getString("quote"))
        assertTrue(selecting.value)
        assertTrue(nativeActionAvailable("Undo highlight")); closeNativeOverflow()
        assertTrue(nativeActionAvailable("Copy")); closeNativeOverflow()
        assertTrue(nativeActionAvailable("Highlight")); closeNativeOverflow()
        assertTrue(nativeActionAvailable("Add note")); closeNativeOverflow()
        assertTrue(nativeActionAvailable("Undo highlight"))
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-auto-highlight-undo", "annotation-reader", recordDensity = false,
            beforeCaptureFrame = ::awaitVisualFrame)
        closeNativeOverflow()
        dragSelectionHandle(textPoint("select-target", 0, 9, "end"), textPoint("select-target", 10, 19, "end"))
        awaitHandleSelection("Selection preserves", "automatic-highlight")
        settle()
        assertEquals("Adjusting the same session cannot save a second highlight", 1, automaticCount.get())
        assertEquals(before, page.get())
        clickNativeAction("Undo highlight")
        compose.waitUntil(5_000) { undoCount.get() == 1 }
        assertTrue(selecting.value)
        settle()
        assertEquals(1, automaticCount.get())
        assertNull(created.get())
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
    }

    @Test fun workerViewportInvalidationRejectsHeldAutomaticResultBeforeOwnerCleanup() {
        startReader()
        compose.runOnIdle { automatic.value = true }
        settle()
        // Hold the real native gesture's complete callback, not its selection.
        // This isolates the queue ordering without sleeps or browser animation.
        js("""
            (function(){window._completeAuto=InkReadAnnotations.completeSelectionGesture;
            InkReadAnnotations.completeSelectionGesture=function(token,generation){
                window._heldAuto={token:token,generation:generation,
                    json:InkReadAnnotations.captureSelection(generation)};return true;};return true;})()
        """.trimIndent())
        longPressFirstWord()
        compose.waitUntil(5_000) { js("!!window._heldAuto") == true }
        val held = JSONObject(js("JSON.stringify(window._heldAuto)") as String)
        assertEquals("Selection", JSONObject(held.getString("json")).getString("quote"))
        assertEquals(0, automaticCount.get())
        instrumentation.runOnMainSync {
            val view = reader() as EInkAnnotationWebView
            val failure = AtomicReference<Throwable?>()
            val worker = Thread({
                try { view.invalidateReaderGesture() }
                catch (error: Throwable) { failure.set(error) }
            }, "reader-layout-regression")
            worker.start()
            worker.join(5_000)
            assertFalse("Invalidation must not wait for the owner thread", worker.isAlive)
            failure.get()?.let { throw AssertionError("Off-owner viewport invalidation failed", it) }
            // The owner queue is still blocked by this same call. Native capture
            // must already be revoked, rather than waiting for posted JS cleanup.
            assertFalse(view.acceptAutomaticCapture(held.getString("token")))
        }
        js("""
            (function(){var held=window._heldAuto;
            Android.onAutoAnnotation(held.generation,held.token,held.json);
            InkReadAnnotations.completeSelectionGesture=window._completeAuto;return true;})()
        """.trimIndent())
        settle()
        assertEquals("A pre-resize snapshot must never persist after invalidation", 0, automaticCount.get())
        assertTrue(selecting.value)
        pressBack()
        compose.waitUntil(5_000) { !selecting.value }
        longPressFirstWord()
        compose.waitUntil(5_000) { automaticCount.get() == 1 }
        assertEquals("Selection", JSONObject(automaticAnchor.get()!!).getString("quote"))
    }

    @Test fun controlsOpenAutomaticSaveAndUndoDoNotReflowNativeSelection() {
        startReader(fontSize = 32)
        compose.runOnIdle { automatic.value = true }
        compose.onNodeWithTag("reader_controls_toggle").performClick()
        awaitDisplayedControl("reader_select_text")
        settle()
        compose.runOnIdle { assertFalse("Opening controls is not explicit selection mode", selecting.value) }
        val viewport = js("JSON.stringify([innerWidth,innerHeight])")
        val before = page.get()
        longPressFirstWord(beforeRelease = { assertEquals(0, automaticCount.get()) })
        compose.waitUntil(5_000) { automaticCount.get() == 1 && undoAvailable.value }
        settle()
        assertEquals("Hidden status/Undo rows must not resize the reader after an automatic save",
            viewport, js("JSON.stringify([innerWidth,innerHeight])"))
        compose.onNodeWithTag("reader_selection_done").assertIsDisplayed()
        nativeHandleAt(textPoint("select-target", 0, 9, "start")).check(matches(isDisplayed()))
        nativeHandleAt(textPoint("select-target", 0, 9, "end")).check(matches(isDisplayed()))
        dragSelectionHandle(textPoint("select-target", 0, 9, "end"), textPoint("select-target", 10, 19, "end"))
        awaitHandleSelection("Selection preserves", "controls-open-automatic-highlight")
        assertEquals(viewport, js("JSON.stringify([innerWidth,innerHeight])"))
        clickNativeAction("Undo highlight")
        compose.waitUntil(5_000) { undoCount.get() == 1 }
        settle()
        assertEquals("Undo/status refresh must retain the same selection viewport",
            viewport, js("JSON.stringify([innerWidth,innerHeight])"))
        assertNativeSelectionRetained("Selection preserves", "controls-open automatic Undo")
        assertEquals(before, page.get())
        assertEquals(1, automaticCount.get())
        compose.onNodeWithTag("reader_selection_done").performClick()
        compose.waitUntil(5_000) { !selecting.value }
        awaitDisplayedControl("reader_annotation_status")
        compose.onNodeWithTag("reader_annotation_status").assertTextEquals("Highlight removed")
        compose.onNodeWithTag("reader_annotation_undo").assertDoesNotExist()
    }

    @Test fun realBrowserCrossParagraphSelectionKeepsDisplayAndRawWhitespace() {
        startReader()
        openSelection()
        // Use real DOM Range/Selection objects in the device WebView to exercise
        // its layout-generated paragraph separators, in addition to gesture tests.
        val snapshot = JSONObject(js("""
            (function(){var model=InkReadScriptFonts.install(document.getElementById('eink-content'));
            var first=model.nodes.find(function(n){return n.parentElement===document.getElementById('select-target');});
            var next=model.nodes.find(function(n){return n.parentElement===document.getElementById('paragraph-0');});
            var a=model.point(first,0,false),b=model.point(next,12,true);
            var range=document.createRange();range.setStart(a.node,a.offset);range.setEnd(b.node,b.offset);
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
            (function(){var target=document.getElementById(${JSONObject.quote(id)});
            var model=InkReadScriptFonts.install(document.getElementById('eink-content'));
            var t=model.nodes.find(function(n){return n.parentElement===target;});
            var a=model.point(t,$start,false),b=model.point(t,$end,true);
            var r=document.createRange();r.setStart(a.node,a.offset);r.setEnd(b.node,b.offset);
            var rects=r.getClientRects(),b=rects[${if (edge == "end") "rects.length-1" else "0"}];
            var x=(b.left+b.right)/2,y=(b.top+b.bottom)/2;
            return JSON.stringify({x:x,y:y,left:b.left,right:b.right,bottom:b.bottom,
                width:document.documentElement.getBoundingClientRect().width,height:innerHeight,
                lineHeight:parseFloat(getComputedStyle(target.closest('p')||target).lineHeight),glyphHeight:b.height,
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
            val point = PointF(origin[0] + x, origin[1] + y)
            val lineHeight = geometry.getDouble("lineHeight").toFloat() * scale
            assertTrue("Native handle lookup requires measured finite leading: $geometry",
                lineHeight.isFinite() && lineHeight > 0)
            endpointAvailableLeading[point] = maxOf(0f, lineHeight - geometry.getDouble("glyphHeight").toFloat() * scale)
            result.set(point)
        }
        return result.get()
    }

    private fun closeNativeOverflow() {
        if (nativeOverflowOpen) {
            // Closing overflow is a toolbar action, not a Back press that could
            // destroy the entire ActionMode before the remaining menu assertions.
            onView(allOf(withContentDescription("Close overflow"), isDisplayed()))
                .inRoot(nativeToolbarRoot()).perform(clickWhenNativeActionTouchable())
            nativeOverflowOpen = false
        }
    }

    private fun nativeHandleAt(endpoint: PointF): androidx.test.espresso.ViewInteraction {
        val matcher = object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("native selection-handle popup at ").appendValue(endpoint)
                    .appendText(" with available measured leading ").appendValue(endpointAvailableLeading[endpoint])
            }
            override fun matchesSafely(view: View): Boolean {
                val density = view.resources.displayMetrics.density
                val params = view.layoutParams as? WindowManager.LayoutParams
                if (params?.type != WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL) return false
                if (!view.isShown || view.width !in 1..(64 * density).toInt() ||
                    view.height !in 1..(96 * density).toInt()) return false
                val origin = IntArray(2)
                view.getLocationOnScreen(origin)
                // A native handle is attached to the line box, not the smaller
                // English glyph's bottom in a Chinese-dominant line. Use the
                // actual leading beyond the glyph height, not an arbitrary pixel radius.
                // Root size/X remain constrained; uniqueness is checked below.
                val belowGlyph = maxOf(32 * density, endpointAvailableLeading[endpoint] ?: 0f)
                Log.i("InkReadAnnotationTest", "Native handle candidate: x=${origin[0]}, y=${origin[1]}, " +
                    "width=${view.width}, height=${view.height}, endpoint=$endpoint, belowGlyph=$belowGlyph")
                return abs(origin[0] + view.width / 2f - endpoint.x) <= 32 * density &&
                    origin[1] >= endpoint.y - 32 * density && origin[1] <= endpoint.y + belowGlyph
            }
        }
        val selectedRoot = AtomicReference<View?>()
        compose.waitUntil(5_000) {
            instrumentation.runOnMainSync {
                val candidates = WindowInspector.getGlobalWindowViews().filter { matcher.matches(it) }
                assertTrue("Native handle targeting must be unambiguous at $endpoint; candidates=${candidates.size}",
                    candidates.size <= 1)
                selectedRoot.set(candidates.singleOrNull())
            }
            selectedRoot.get() != null
        }
        // Espresso may choose one of several matching roots. Pin the explicitly
        // unique native popup instead of relying on its root-picker ordering.
        return onView(isRoot()).inRoot(withDecorView(equalTo(checkNotNull(selectedRoot.get()))))
    }

    private fun dragSelectionHandle(from: PointF, to: PointF) {
        logNativeHandleState("before-drag", from = from, to = to)
        nativeHandleAt(from).perform(realTimeDrag(
            start = { view -> screenPoint(view, .5f, .5f) },
            end = { _, start -> PointF(to.x + start.x - from.x, to.y + start.y - from.y) },
        ))
        logNativeHandleState("after-drag", from = from, to = to)
    }

    private fun awaitHandleSelection(expected: String, context: String) {
        try {
            compose.waitUntil(5_000) { js("window.getSelection().toString().trim()") == expected }
        } catch (timeout: ComposeTimeoutException) {
            // Observe the failed gesture, never repeat it or manufacture a range.
            // Diagnostic failures must not replace the original exact-text timeout.
            logNativeHandleState("timeout-$context", expected = expected)
            runCatching {
                val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                val directory = output?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(instrumentation.targetContext.getExternalFilesDir(null), "annotation-diagnostics")
                check(directory.isDirectory || directory.mkdirs())
                val screenshot = File(directory, "annotation-handle-timeout-$context.png")
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    screenshot.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
                Log.i("InkReadAnnotationTest", "Native handle timeout screenshot: ${screenshot.name}")
            }.onFailure { Log.w("InkReadAnnotationTest", "Native handle timeout screenshot unavailable", it) }
            throw timeout
        }
    }

    private fun logNativeHandleState(phase: String, expected: String? = null, from: PointF? = null, to: PointF? = null) {
        runCatching {
            val state = JSONObject().put("phase", phase).put("uptimeMs", SystemClock.uptimeMillis())
                .put("expected", expected ?: JSONObject.NULL)
                .put("from", from?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL)
                .put("to", to?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL)
            instrumentation.runOnMainSync {
                val view = reader() as EInkAnnotationWebView
                state.put("host", JSONObject().put("selecting", selecting.value)
                    .put("hasActiveSelection", view.hasActiveSelection).put("hasFocus", view.hasFocus())
                    .put("hasWindowFocus", view.hasWindowFocus()).put("attached", view.isAttachedToWindow)
                    .put("width", view.width).put("height", view.height).put("page", page.get()))
            }
            state.put("dom", JSONObject(js("""
                (function(){
                    var s=getSelection(),r=s&&s.rangeCount?s.getRangeAt(0):null;
                    function rect(r){return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};}
                    var target=document.getElementById('select-target'),block=target&&target.closest('p');
                    function layout(e){if(!e)return null;var c=getComputedStyle(e);return {tag:e.tagName,
                        fontSize:c.fontSize,lineHeight:c.lineHeight,padding:[c.paddingTop,c.paddingBottom],
                        border:[c.borderTopWidth,c.borderBottomWidth],rects:Array.from(e.getClientRects()).map(rect)};}
                    function node(n){return n?{type:n.nodeType,name:n.nodeName,text:(n.textContent||'').slice(0,256),
                        parentId:n.parentElement&&n.parentElement.id,parentClass:n.parentElement&&n.parentElement.className}:null;}
                    return JSON.stringify({text:s?s.toString():null,collapsed:s?s.isCollapsed:null,rangeCount:s?s.rangeCount:0,
                        raw:r?r.toString():null,start:node(r&&r.startContainer),startOffset:r?r.startOffset:null,
                        end:node(r&&r.endContainer),endOffset:r?r.endOffset:null,
                        anchor:node(s&&s.anchorNode),anchorOffset:s?s.anchorOffset:null,
                        focus:node(s&&s.focusNode),focusOffset:s?s.focusOffset:null,
                        generation:window._inkreadGeneration,viewport:[innerWidth,innerHeight],
                        rangeRects:r?Array.from(r.getClientRects()).map(rect):[],target:layout(target),block:layout(block)});
                })()
            """.trimIndent()) as String))
            Log.i("InkReadAnnotationTest", "Native handle state: $state")
        }.onFailure { Log.w("InkReadAnnotationTest", "Native handle state unavailable: $phase", it) }
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
                .inRoot(nativeToolbarRoot()).perform(clickWhenNativeActionTouchable())
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
                .inRoot(nativeToolbarRoot()).perform(clickWhenNativeActionTouchable())
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

    private fun clickNativeAction(label: String) {
        nativeActionAvailable(label)
        nativeAction(label).perform(clickWhenNativeActionTouchable())
        nativeOverflowOpen = false
    }

    private fun clickWhenNativeActionTouchable(): ViewAction {
        if (originalAccessibilityFlags == null) {
            val automation = instrumentation.uiAutomation
            val info = automation.serviceInfo
            originalAccessibilityFlags = info.flags
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
        }
        val originalClick = click()
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> = originalClick.constraints
            override fun getDescription() = "one native click after its window can receive the click center"
            override fun perform(uiController: UiController, view: View) {
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (true) {
                    val (ready, state) = nativeActionTouchability(view)
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    assertTrue("Native action was not touchable within 5000 ms: $state", remaining > 0)
                    if (ready) {
                        // Preserve the real Espresso gesture, coordinates and
                        // exact-result assertions. Never retry a missed click.
                        originalClick.perform(uiController, view)
                        return
                    }
                    // Pump UI work between observations, rather than sleeping
                    // through a fixed toolbar animation or blocking its frames.
                    uiController.loopMainThreadForAtLeast(minOf(16L, remaining))
                }
            }
        }
    }

    private fun nativeActionTouchability(target: View): Pair<Boolean, String> {
        // isDisplayed alone is insufficient: Android can retain alpha=1 menu
        // views while its floating toolbar's touchable region is empty.
        val center = GeneralLocation.VISIBLE_CENTER.calculateCoordinates(target)
        val ancestors = mutableListOf<String>()
        var visible = true
        var current: View? = target
        while (current != null) {
            val node = current
            visible = visible && node.isAttachedToWindow && node.isShown && node.isEnabled && node.alpha == 1f
            ancestors += "${node.javaClass.simpleName}(attached=${node.isAttachedToWindow}," +
                "shown=${node.isShown},enabled=${node.isEnabled},alpha=${node.alpha})"
            current = node.parent as? View
        }
        val accessibilityNode = target.createAccessibilityNodeInfo()
        val targetWindow = accessibilityNode.windowId
        accessibilityNode.recycle()
        val windows = instrumentation.uiAutomation.windows
        var containsCenter = false
        val regions = mutableListOf<String>()
        try {
            for (window in windows) {
                val region = Region()
                window.getRegionInScreen(region)
                if (window.id == targetWindow) {
                    containsCenter = region.contains(center[0].toInt(), center[1].toInt())
                }
                regions += "${window.id}:$region"
            }
        } finally {
            windows.forEach { it.recycle() }
        }
        return (visible && containsCenter) to "target=${(target as? TextView)?.text ?: target.contentDescription}," +
            "center=${center.contentToString()},window=$targetWindow,containsCenter=$containsCenter," +
            "ancestors=$ancestors,regions=$regions"
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
