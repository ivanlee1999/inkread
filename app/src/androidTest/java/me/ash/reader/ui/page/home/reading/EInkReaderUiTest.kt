package me.ash.reader.ui.page.home.reading

import me.ash.reader.infrastructure.android.VolumeKeyEvent
import me.ash.reader.infrastructure.android.VolumeKeyEventBus
import me.ash.reader.infrastructure.android.VolumeKeyPriority
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.atomic.AtomicReference
import me.ash.reader.infrastructure.preference.LocalEInkFontSize
import me.ash.reader.infrastructure.preference.LocalEInkEnglishFont
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EInkReaderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paginateSwitchAndReopenControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val font = File(context.filesDir, "fonts/SourceSerif4-Regular.ttf")
        font.parentFile!!.mkdirs()
        File("/system/fonts/NotoSerif-Regular.ttf").copyTo(font, overwrite = true)
        val article = mutableStateOf(fixture("First"))
        val title = mutableStateOf("First article")
        val page = AtomicInteger()
        val count = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON, LocalEInkEnglishFont provides 1) {
                AppTheme(useDarkTheme = false) {
                    EInkPaginatedContent(content = article.value, title = title.value,
                        feedName = "Regression fixture", publishedDate = Date(0),
                        onPageChanged = { current, total -> page.set(current); count.set(total) })
                }
            }
        }
        compose.waitUntil(15_000) { count.get() > 2 }
        val fontsReady = AtomicReference<String>()
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val webView = findWebView(activity.window.decorView)!!
            webView.evaluateJavascript("document.fonts.size > 0 && document.fonts.check('20px SourceSerif4')") { fontsReady.set(it) }
        }
        compose.waitUntil(5_000) { fontsReady.get() == "true" }
        compose.onRoot().performTouchInput { click(Offset(width * .8f, height * .5f)) }
        compose.waitUntil(5_000) { page.get() == 2 }
        // Controls fit without overlap on both 320dp phone and tablet layouts.
        compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .9f)) }
        compose.onNodeWithContentDescription("Next page").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { page.get() == 3 }
        compose.onNodeWithText("A↑").assertIsDisplayed()
        val output = File(context.filesDir, "eink-reader-${context.resources.configuration.screenWidthDp}.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Trigger multiple changes before old-document delayed recounts have finished.
        compose.runOnIdle { article.value = fixture("Second"); title.value = "Second article"; page.set(0); count.set(0) }
        compose.waitUntil(15_000) { count.get() > 2 && page.get() == 1 }
        compose.runOnIdle { article.value = fixture("First"); title.value = "First article"; page.set(0); count.set(0) }
        compose.waitUntil(15_000) { count.get() > 2 && page.get() == 1 }
        compose.onNodeWithContentDescription("Next page").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { page.get() == 2 }
    }

    @Test fun identicalArticlesCanNavigateRepeatedly() {
        val identity = mutableStateOf("article-0")
        val navigations = AtomicInteger()
        val count = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                AppTheme(useDarkTheme = false) {
                    EInkPaginatedContent(articleId = identity.value, content = "<p>Identical content</p>",
                        title = "Identical title", feedName = "Identical feed", publishedDate = Date(0),
                        onPageChanged = { _, total -> count.set(total) },
                        onNextArticle = { identity.value = "article-${navigations.incrementAndGet()}" })
                }
            }
        }
        compose.waitUntil(15_000) { count.get() == 1 }
        for (expected in 1..3) {
            compose.onRoot().performTouchInput { click(Offset(width * .8f, height * .5f)) }
            compose.waitUntil(5_000) { navigations.get() == expected }
        }
    }

    @Test fun persistsAnchorAcrossFontReflowAndRendererRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "resume-${UUID.randomUUID()}"
        val displayed = mutableStateOf(true)
        val fontSize = mutableStateOf(20)
        val viewportFraction = mutableStateOf(1f)
        val page = AtomicInteger()
        val count = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalEInkFontSize provides fontSize.value) {
                AppTheme(useDarkTheme = false) {
                    Box(Modifier.fillMaxWidth(viewportFraction.value).fillMaxHeight()) {
                        if (displayed.value) EInkPaginatedContent(articleId = identity, accountId = 812,
                            content = fixture("Persistent").repeat(3), title = "Resume", feedName = "Fixture",
                            publishedDate = Date(0), onPageChanged = { current, total -> page.set(current); count.set(total) })
                    }
                }
            }
        }
        compose.waitUntil(15_000) { count.get() > 6 }
        repeat(4) {
            compose.onRoot().performTouchInput { click(Offset(width * .8f, height * .5f)) }
            compose.waitUntil(5_000) { page.get() == it + 2 }
        }
        val directory = File(context.filesDir, "reading-resume")
        var saved: EInkReadingPosition? = null
        compose.waitUntil(5_000) {
            saved = EInkReadingPositionStore(directory).load(812, identity)
            saved?.let { it.characterOffset > 0 &&
                kotlin.math.abs(it.fraction - (page.get() - 1.0) / (count.get() - 1)) < 1e-9 } == true
        }
        val anchor = saved!!.characterOffset
        val originalPage = page.get()
        compose.runOnIdle { fontSize.value = 28; page.set(0); count.set(0) }
        compose.waitUntil(15_000) { count.get() > 6 && page.get() > 1 }
        compose.waitUntil(5_000) { EInkReadingPositionStore(directory).load(812, identity)?.characterOffset == anchor }
        val enlargedPage = page.get()
        assertAnchorAfterDelayedReflows(directory, identity, anchor)
        compose.runOnIdle { fontSize.value = 20; page.set(0); count.set(0) }
        compose.waitUntil(15_000) { count.get() > 6 && page.get() == originalPage }
        assertAnchorAfterDelayedReflows(directory, identity, anchor)
        compose.runOnIdle { fontSize.value = 28; page.set(0); count.set(0) }
        compose.waitUntil(15_000) { count.get() > 6 && page.get() == enlargedPage }
        assertAnchorAfterDelayedReflows(directory, identity, anchor)
        // A real WebView viewport resize exercises the same layout path as rotation.
        val originalCount = count.get()
        compose.runOnIdle { viewportFraction.value = .75f }
        compose.waitUntil(10_000) { count.get() > originalCount }
        assertAnchorAfterDelayedReflows(directory, identity, anchor)
        compose.runOnIdle { viewportFraction.value = 1f }
        compose.waitUntil(10_000) { count.get() == originalCount && page.get() == enlargedPage }
        assertAnchorAfterDelayedReflows(directory, identity, anchor)
        compose.runOnIdle { displayed.value = false }
        compose.waitForIdle()
        compose.runOnIdle { displayed.value = true; page.set(0); count.set(0) }
        compose.waitUntil(15_000) { count.get() > 6 && page.get() == enlargedPage }
        assertEquals(anchor, EInkReadingPositionStore(directory).load(812, identity)!!.characterOffset)
        // Moving backwards stores the current location rather than furthest progress.
        compose.onRoot().performTouchInput { click(Offset(width * .2f, height * .5f)) }
        compose.waitUntil(5_000) { page.get() == enlargedPage - 1 }
        compose.waitUntil(5_000) { EInkReadingPositionStore(directory).load(812, identity)!!.characterOffset < anchor }
    }

    @Test fun loadingReaderKeepsPriorityAndDiscardsPresses() {
        val loading = mutableStateOf(true)
        val page = AtomicInteger()
        val count = AtomicInteger()
        VolumeKeyEventBus.register(VolumeKeyPriority.LOW)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON) {
                    AppTheme(useDarkTheme = false) {
                        val flow = rememberEInkReaderVolumeKeyFlow(visible = true, loading = loading.value)
                        if (loading.value) Text("Loading fixture")
                        else EInkPaginatedContent(registeredVolumeKeyFlow = flow,
                            content = fixture("Loading"), title = "Loading regression", feedName = "Fixture",
                            publishedDate = Date(0), onPageChanged = { current, total -> page.set(current); count.set(total) })
                    }
                }
            }
            compose.waitForIdle()
            assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH))
            assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }
            compose.runOnIdle { loading.value = false }
            compose.waitUntil(15_000) { count.get() > 2 && page.get() == 1 }
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }
            compose.waitUntil(5_000) { page.get() == 2 }
            compose.runOnIdle { loading.value = true }
            compose.waitForIdle()
            assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH))
            assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
            compose.runOnIdle { VolumeKeyEventBus.emit(VolumeKeyEvent.PREV) }
            compose.runOnIdle { loading.value = false; page.set(0); count.set(0) }
            compose.waitUntil(15_000) { count.get() > 2 && page.get() == 1 }
        } finally {
            VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW)
        }
    }

    private fun assertAnchorAfterDelayedReflows(directory: File, identity: String, anchor: Int) {
        val result = AtomicReference<String>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Exercise the same callback loop as viewport/image reflow; native must only mirror state.
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            findWebView(activity.window.decorView)!!.evaluateJavascript("reflow(); reflow(); _anchorOffset") { result.set(it) }
        }
        compose.waitUntil(5_000) { result.get() != null }
        assertEquals(anchor.toString(), result.get())
        val started = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(5_000) {
            android.os.SystemClock.uptimeMillis() - started >= 1700 &&
                EInkReadingPositionStore(directory).load(812, identity)?.characterOffset == anchor
        }
        result.set(null)
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            findWebView(activity.window.decorView)!!.evaluateJavascript("_anchorOffset") { result.set(it) }
        }
        compose.waitUntil(5_000) { result.get() != null }
        assertEquals(anchor.toString(), result.get())
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun fixture(label: String): String = buildString {
        append("<p>$label article. 中文阅读 &amp; escaped &#60;script&#62; text.</p>")
        repeat(60) { append("<p>Paragraph $it: readable text fills several pages. Touch navigation stays responsive on narrow and tablet displays.</p>") }
        append("<pre>${"long_code_without_spaces_".repeat(100)}</pre>")
        append("<table><tr><td>Wide table</td><td>${"data".repeat(50)}</td></tr></table>")
    }
}
