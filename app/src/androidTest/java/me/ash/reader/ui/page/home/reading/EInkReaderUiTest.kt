package me.ash.reader.ui.page.home.reading

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.ash.reader.infrastructure.preference.LocalReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderFormatting
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.ui.theme.AppTheme
import me.ash.reader.ui.page.home.NativePageDensityCapture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONTokener

class EInkReaderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paginateSwitchAndReopenControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val font = File(context.filesDir, "fonts/SourceSerif4-Regular.ttf")
        font.parentFile!!.mkdirs()
        val previousFont = font.takeIf { it.isFile }?.readBytes()
        try {
            File("/system/fonts/NotoSerif-Regular.ttf").copyTo(font, overwrite = true)
            val article = mutableStateOf(fixture("First"))
            val title = mutableStateOf("First article")
            val page = AtomicInteger()
            val count = AtomicInteger()
            compose.setContent {
                CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON, LocalReaderFormatting provides ReaderFormatting.Default.copy(englishFont = 1)) {
                    AppTheme(useDarkTheme = false) {
                        EInkPaginatedContent(content = article.value, title = title.value,
                            feedName = "Regression fixture", author = "Fixture Reporter", publishedDate = Date(0),
                            onPageChanged = { current, total -> page.set(current); count.set(total) })
                    }
                }
            }
            compose.waitUntil(15_000) { count.get() > 2 }
            val fontsReady = AtomicReference<String>()
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
                val webView = findWebView(activity.window.decorView)!!
                webView.evaluateJavascript("""
                    (function() {
                        var frame = document.getElementById('eink-content');
                        var css = document.getElementById('eink-font-faces');
                        return !!frame && !!css && css.textContent.includes('SourceSerif4-Regular.ttf') &&
                            getComputedStyle(frame).fontFamily.replace(/["']/g, '').indexOf('InkReadLatin') === 0 &&
                            Array.from(document.fonts).some(function(f) {
                                return f.family.replace(/["']/g, '') === 'InkReadLatin' && f.status === 'loaded';
                            }) && document.fonts.check('20px InkReadLatin', 'Readable Latin text');
                    })()
                """.trimIndent()) { fontsReady.set(it) }
            }
            compose.waitUntil(5_000) { fontsReady.get() == "true" }
            val metadata = AtomicReference<String>()
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
                findWebView(activity.window.decorView)!!.evaluateJavascript("document.querySelector('.eink-meta').textContent.includes('Regression fixture · Fixture Reporter')") { metadata.set(it) }
            }
            compose.waitUntil(5_000) { metadata.get() == "true" }
            NativePageDensityCapture.capture(compose, "editorial-reader-page", "", recordDensity = false,
                contentBoundsOnScreen = ::documentBodyBounds, beforeCaptureFrame = ::awaitDocumentFrame)
            compose.onRoot().performTouchInput { click(Offset(width * .8f, height * .5f)) }
            compose.waitUntil(5_000) { page.get() == 2 }
            // Controls fit without overlap on both 320dp phone and tablet layouts.
            compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .9f)) }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Next page").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithContentDescription("Next page").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { page.get() == 3 }
            compose.onNodeWithText("A↑").assertIsDisplayed()
            NativePageDensityCapture.capture(compose, "editorial-reader-controls", "", recordDensity = false,
                contentBoundsOnScreen = ::documentBodyBounds, beforeCaptureFrame = ::awaitDocumentFrame)
            // Trigger multiple changes before old-document delayed recounts have finished.
            compose.runOnIdle { article.value = fixture("Second"); title.value = "Second article"; page.set(0); count.set(0) }
            compose.waitUntil(15_000) { count.get() > 2 && page.get() == 1 }
            compose.runOnIdle { article.value = fixture("First"); title.value = "First article"; page.set(0); count.set(0) }
            compose.waitUntil(15_000) { count.get() > 2 && page.get() == 1 }
            compose.onNodeWithContentDescription("Next page").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { page.get() == 2 }
        } finally {
            if (previousFont == null) check(!font.exists() || font.delete())
            else font.writeBytes(previousFont)
        }
    }

    @Test fun identicalArticlesCanNavigateRepeatedly() {
        val identity = mutableStateOf("article-0")
        val navigations = AtomicInteger()
        val count = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalReaderFormatting provides ReaderFormatting.Default) {
                AppTheme(useDarkTheme = false) {
                    EInkPaginatedContent(articleId = identity.value, content = "<p>Identical content</p>",
                        title = "Identical title", feedName = "Identical feed", publishedDate = Date(0),
                        onPageChanged = { _, total -> count.set(total) },
                        onNextArticle = {
                            count.set(0)
                            identity.value = "article-${navigations.incrementAndGet()}"
                        })
                }
            }
        }
        compose.waitUntil(15_000) { count.get() == 1 }
        var visibleGeneration: String? = null
        for (expected in 1..3) {
            // A page-count callback precedes initial rendering. Each native tap must target
            // the next visible document, not the transparent document-loading interval.
            visibleGeneration = awaitVisibleSinglePageDocument(visibleGeneration)
            compose.onRoot().performTouchInput { click(Offset(width * .8f, height * .5f)) }
            compose.waitUntil(5_000) { navigations.get() == expected }
        }
    }

    private fun awaitVisibleSinglePageDocument(previousGeneration: String?): String {
        var generation: String? = null
        compose.waitUntil(15_000) {
            val value = AtomicReference<String>()
            val callback = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
                findWebView(activity.window.decorView)!!.evaluateJavascript("""
                    (function() {
                        var frame = document.getElementById('eink-content');
                        var bounds = frame && frame.getBoundingClientRect();
                        return document.readyState === 'complete' && frame && bounds.width > 0 && bounds.height > 0 &&
                            getComputedStyle(frame).visibility === 'visible' &&
                            window.InkReadFormatting && InkReadFormatting.documentId === window._inkreadGeneration &&
                            window._didFinishInitialPagination === true && window._totalPages === 1
                            ? window._inkreadGeneration : null;
                    })()
                """.trimIndent()) { value.set(it); callback.countDown() }
            }
            assertTrue("The active reader must answer its readiness query", callback.await(5, TimeUnit.SECONDS))
            generation = JSONTokener(value.get()).nextValue() as? String
            generation != null && generation != previousGeneration
        }
        compose.waitForIdle()
        awaitDocumentFrame()
        return requireNotNull(generation)
    }

    private fun awaitDocumentFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val webView = requireNotNull(findWebView(activity.window.decorView))
            webView.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { ready.countDown() }
            })
        }
        assertTrue("The real WebView document must reach visual readiness before capture", ready.await(10, TimeUnit.SECONDS))
    }

    private fun documentBodyBounds(): Rect {
        var bounds: Rect? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val webView = requireNotNull(findWebView(activity.window.decorView))
            val origin = IntArray(2)
            webView.getLocationOnScreen(origin)
            check(webView.isShown && webView.width > 0 && webView.height > 0)
            // This known long document fills the center of each captured page with body text.
            // Exclude the title, metadata, edge borders, controls, and all system bars: none may
            // conceal a white/unrendered HTML body. Native pixels, not DOM readiness, decide.
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

    private fun fixture(label: String): String = buildString {
        append("<p>$label article. 中文阅读 &amp; escaped &#60;script&#62; text.</p>")
        repeat(60) { append("<p>Paragraph $it: readable text fills several pages. Touch navigation stays responsive on narrow and tablet displays.</p>") }
        append("<pre>${"long_code_without_spaces_".repeat(100)}</pre>")
        append("<table><tr><td>Wide table</td><td>${"data".repeat(50)}</td></tr></table>")
    }
}
