package me.ash.reader.ui.page.home.reading

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ListView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
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
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.dispatchEInkVolumeKey
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.infrastructure.preference.LocalReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderFormatting
import me.ash.reader.ui.theme.AppTheme
import org.hamcrest.Description
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
    private val created = AtomicReference<String?>()
    private val note = AtomicReference<Boolean?>()
    private val locations = AtomicReference<String?>()
    private var nativeOverflowOpen = false
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun prepare() { VolumeKeyEventBus.resetForTest() }
    @After fun cleanup() { VolumeKeyEventBus.resetForTest() }

    private fun startReader() {
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalReaderFormatting provides ReaderFormatting.Default) {
                AppTheme(useDarkTheme = false) {
                    EInkPaginatedContent(modifier = Modifier.testTag("annotation-reader"),
                        articleId = "annotation-fixture", title = "Annotation fixture", feedName = "Local fixture",
                        publishedDate = Date(0), content = buildString {
                            append("<p id='select-target'>Selection preserves exact text, 中文 and emoji 😀.</p>")
                            repeat(70) { append("<p>Paragraph $it. Long article text exercises native paging and annotation ownership.</p>") }
                        },
                        selectionMode = selecting.value, onSelectionModeChange = { selecting.value = it },
                        annotationEditing = editing.value, annotationsJson = annotationJson.value,
                        onCreateAnnotation = { json, addNote -> created.set(json); note.set(addNote); editing.value = addNote },
                        onAnnotationLocationsChanged = { locations.set(it) }, onAnnotationsClick = {},
                        onBack = { exits.incrementAndGet() },
                        onPageChanged = { current, count -> page.set(current); total.set(count) })
                }
            }
        }
        compose.waitUntil(15_000) { total.get() > 2 }
        assertEquals(true, js("!!window.InkReadAnnotations"))
    }

    private fun openSelection() {
        compose.onNodeWithTag("reader_controls_toggle").performClick()
        compose.onNodeWithTag("reader_select_text").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader_selection_done").assertIsDisplayed()
        compose.onNodeWithTag("reader_controls_toggle").assertDoesNotExist()
        compose.waitUntil(5_000) { selecting.value && js("window.innerHeight > 0") == true }
        // Wait for the measured Done-row viewport's real reflow before the gesture.
        awaitVisualFrame()
    }

    private fun longPressFirstWord() {
        // Range geometry is read-only: native touch must create the Selection and
        // ActionMode. Never call addRange(), performLongClick() or the menu callback.
        val point = js("""
            (function(){var target=document.getElementById('select-target'),t=target.firstChild;
            var r=document.createRange();r.setStart(t,0);r.setEnd(t,9);var b=r.getClientRects()[0];
            var x=(b.left+b.right)/2,y=(b.top+b.bottom)/2;
            return JSON.stringify({x:x,y:y,width:document.documentElement.getBoundingClientRect().width,
                height:innerHeight,word:r.toString(),hit:target.contains(document.elementFromPoint(x,y)),
                ready:document.readyState==='complete' && window._didFinishInitialPagination &&
                    InkReadAnnotations.documentId===window._inkreadGeneration,
                selectable:getComputedStyle(target).webkitUserSelect!=='none'});})()
        """.trimIndent()) as String
        val p = JSONObject(point)
        assertEquals("Selection", p.getString("word"))
        assertTrue("The live article fixture must be paginated: $point", p.getBoolean("ready"))
        assertTrue("The gesture must hit visible article text: $point", p.getBoolean("hit"))
        assertTrue("Article text must permit native selection: $point", p.getBoolean("selectable"))
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
                false // Observe only; normal WebView hit testing and native handling own the gesture.
            }
        }
        try {
            // Compose longClick batches DOWN/UP while advancing only its test clock.
            // WebView's native long-press timer needs real elapsed time with DOWN held.
            // Espresso injects through Android's window dispatch, so a Compose overlay
            // still intercepts (and fails) this gesture rather than being bypassed.
            onView(isAssignableFrom(EInkAnnotationWebView::class.java)).perform(GeneralClickAction(
                Tap.LONG,
                { view ->
                    val webView = view as EInkAnnotationWebView
                    assertTrue("Selection mode must reach the native WebView", webView.selectionEnabled())
                    assertTrue("The native reader must own focus", webView.hasFocus() && webView.hasWindowFocus())
                    val origin = IntArray(2)
                    webView.getLocationOnScreen(origin)
                    val scale = webView.width / p.getDouble("width").toFloat()
                    assertEquals("The DOM viewport must match the resized native reader",
                        webView.height.toDouble(), p.getDouble("height") * scale, scale.toDouble() + 1)
                    val x = p.getDouble("x").toFloat() * scale
                    val y = p.getDouble("y").toFloat() * scale
                    assertTrue("Long-press must remain inside the reader", x > 0 && x < webView.width && y > 0 && y < webView.height)
                    Log.i("InkReadAnnotationTest", "Native long-press: dom=$point origin=${origin.toList()} scale=$scale local=[$x,$y]")
                    floatArrayOf(origin[0] + x, origin[1] + y)
                },
                Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0,
            ))
            assertTrue("The real WebView must receive DOWN, not an overlay: $touchEvents", downAt.get() >= 0)
            compose.waitUntil(5_000) { js("window.getSelection().toString().length > 0") == true }
        } finally {
            instrumentation.runOnMainSync { reader().setOnTouchListener(null) }
            Log.i("InkReadAnnotationTest", "Native long-press delivered: heldMs=${heldFor.get()} events=$touchEvents selecting=${selecting.value}")
        }
    }

    @Test fun realLongPressRetainsCopyAndBackLeavesSelectionBeforeReader() {
        startReader()
        openSelection()
        longPressFirstWord()
        assertTrue("The native floating toolbar must keep Copy", nativeActionAvailable("Copy"))
        me.ash.reader.ui.page.home.NativePageDensityCapture.capture(compose,
            "annotation-native-selection", "annotation-reader", recordDensity = false,
            beforeCaptureFrame = ::awaitVisualFrame)
        if (nativeOverflowOpen) { pressBack(); nativeOverflowOpen = false }
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
        pressBack()
        compose.waitUntil(5_000) { exits.get() == 1 }
    }

    @Test fun nativeAddNoteCapturesBeforeSelectionClearAndSuppressesEditingVolume() {
        startReader()
        openSelection()
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
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(5_000) { page.get() == before + 1 }
    }

    @Test fun nativeHighlightRendersWithoutEditingBodyTextAndDoneRestoresPaging() {
        startReader()
        val before = js("document.getElementById('eink-content').innerHTML") as String
        openSelection()
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
        val current = page.get()
        volume(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(5_000) { page.get() == current + 1 }
        volume(KeyEvent.KEYCODE_VOLUME_UP)
        compose.waitUntil(5_000) { page.get() == current }
        openSelection()
        compose.onNodeWithTag("reader_selection_done").performClick()
        compose.waitUntil(5_000) { !selecting.value }
        assertEquals(0, exits.get())
    }

    @Test fun nativeBackCancelsHeldCaptureBeforeItCanOpenANote() {
        startReader()
        openSelection()
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

    @Test fun realBrowserCrossParagraphSelectionKeepsDisplayAndRawWhitespace() {
        startReader()
        openSelection()
        // Use real DOM Range/Selection objects in the device WebView to exercise
        // its layout-generated paragraph separators, in addition to gesture tests.
        val snapshot = JSONObject(js("""
            (function(){var first=document.getElementById('select-target').firstChild;
            var next=document.getElementById('select-target').nextElementSibling.firstChild;
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
    private fun clickNativeAction(label: String) {
        nativeActionAvailable(label)
        nativeAction(label).perform(click())
        nativeOverflowOpen = false
    }
    private fun volume(code: Int) = instrumentation.runOnMainSync {
        assertTrue(dispatchEInkVolumeKey(true, code, KeyEvent.ACTION_DOWN, 0))
        assertTrue(dispatchEInkVolumeKey(true, code, KeyEvent.ACTION_UP, 0))
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
