package me.ash.reader.ui.page.home.reading

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.ash.reader.infrastructure.android.dispatchEInkVolumeKey
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.LocalEInkFontSize
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.ui.page.home.NativePageDensityCapture
import me.ash.reader.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Benign content through the real reader document, WebView and navigation controller.
 * Only two ordinary image responses are supplied in memory; document loading and all other
 * requests still use the production WebViewClient. No renderer/bridge replacement is used.
 * Run at density 160 on phone/tablet AND physical width 1080 / density 420: the latter has a
 * fractional CSS viewport, which integer-dp-only screenshots cannot exercise.
 */
class EInkReaderAlignmentUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val samples = JSONArray()
    private val violations = mutableListOf<String>()
    private val page = AtomicInteger()
    private val count = AtomicInteger()
    private val lateRequested = CountDownLatch(1)
    private val releaseLateImage = CountDownLatch(1)
    private val lateDelivered = CountDownLatch(1)
    private val identity = mutableStateOf(0)
    private val content = mutableStateOf("<p>Reader layout fixture loading.</p>")
    private val fontSize = mutableStateOf(18)
    private val viewportFraction = mutableStateOf(1f)

    @Test fun cjkPagesStayCenteredThroughRepeatedTurnsImagesAndReflow() {
        compose.setContent {
            CompositionLocalProvider(LocalEInkMode provides EInkModePreference.ON,
                LocalEInkFontSize provides fontSize.value) {
                AppTheme(useDarkTheme = false) {
                    Box(Modifier.fillMaxSize()) {
                        EInkPaginatedContent(
                            modifier = Modifier.fillMaxWidth(viewportFraction.value)
                                .fillMaxHeight(viewportFraction.value).testTag("alignment-reader"),
                            articleId = "alignment-${identity.value}", content = content.value,
                            title = "中文阅读：页面留白测试 ${identity.value}",
                            feedName = "Ordinary image layout fixture", publishedDate = Date(0),
                            onPageChanged = { current, total -> page.set(current); count.set(total) },
                            onNextArticle = { page.set(0); count.set(0); identity.value++ },
                            onPrevArticle = { page.set(0); count.set(0); identity.value-- },
                        )
                    }
                }
            }
        }
        try {
            compose.waitUntil(15_000) { count.get() > 0 }
            installOrdinaryImageResponses()
            compose.runOnIdle { page.set(0); count.set(0); content.value = fixture() }
            awaitFirstPage()
            assertTrue("The late ordinary image must actually be requested", lateRequested.await(10, TimeUnit.SECONDS))
            assertEquals("The second image is intentionally still decoding/loading", false,
                geometry().getJSONObject("lateImage").getBoolean("complete"))
            compose.waitUntil(15_000) {
                geometry().let { value ->
                    val image = value.getJSONObject("ordinaryImage")
                    image.getBoolean("complete") && image.getInt("naturalWidth") > 0 && value.getInt("totalPages") == count.get()
                }
            }
            record("initial", screenshot = true)
            // Travel far enough to expose cumulative offsets, not just alternating pages 1/2.
            traverse("tap-before-image", volume = false)
            repeat(12) { turn(forward = true, volume = true) }
            record("before-late-image", screenshot = true)
            releaseLateImage.countDown()
            assertTrue("The ordinary JPEG response must be delivered", lateDelivered.await(10, TimeUnit.SECONDS))
            compose.waitUntil(15_000) {
                geometry().getJSONObject("lateImage").let { it.getBoolean("complete") && it.getInt("naturalWidth") > 0 }
            }
            awaitVisualFrame()
            record("after-late-image", screenshot = true)
            repeat(12) { turn(forward = false, volume = true); record("image-back-$it") }
            traverse("volume-after-image", volume = true)

            // Font preference reloads the actual production document; resizing reflows the same
            // WebView. Both must keep the full page frame centered, not center-align paragraphs.
            compose.runOnIdle { page.set(0); count.set(0); fontSize.value = 32 }
            awaitFirstPage()
            traverse("large-font", volume = true)
            val oldWidth = geometry().getDouble("viewportWidth")
            compose.runOnIdle { viewportFraction.value = .83f }
            compose.waitUntil(10_000) {
                geometry().let { value ->
                    // Resize repagination is debounced in production. A new WebView width or
                    // committed frame alone does not mean its column stride has caught up.
                    value.getDouble("viewportWidth") < oldWidth - 5 && value.getDouble("stride") < oldWidth - 5 &&
                        abs(value.getDouble("stride") - value.getDouble("viewportWidth")) < 1 &&
                        value.getInt("totalPages") == count.get()
                }
            }
            compose.waitForIdle()
            awaitVisualFrame()
            traverse("resized", volume = false)

            // Horizontal swipes are ARTICLE shortcuts in this product, not page turns.
            // Exercise next/previous article reset twelve times without changing that behavior.
            repeat(12) { round ->
                swipeArticle(forward = true)
                record("swipe-next-$round", screenshot = round == 11)
                swipeArticle(forward = false)
                record("swipe-previous-$round", screenshot = round == 11)
            }
            assertTrue("Reader geometry violations:\n${violations.joinToString("\n")}", violations.isEmpty())
        } finally {
            releaseLateImage.countDown()
            writeReport()
        }
    }

    private fun traverse(label: String, volume: Boolean) {
        assertEquals("Each traversal starts on the first page", 1, page.get())
        assertTrue("Fixture must reach at least page 13 at this viewport/font", count.get() >= 13)
        val pagesBefore = count.get()
        record("$label-first", screenshot = true)
        repeat(12) { index ->
            turn(forward = true, volume = volume)
            record("$label-forward-${index + 1}", screenshot = index == 5 || index == 11)
        }
        repeat(12) { index ->
            turn(forward = false, volume = volume)
            record("$label-back-${index + 1}")
        }
        assertEquals("Unchanged content cannot gain/lose pages during turns", pagesBefore, count.get())
    }

    private fun turn(forward: Boolean, volume: Boolean) {
        val expected = page.get() + if (forward) 1 else -1
        if (volume) {
            val code = if (forward) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP
            compose.runOnIdle {
                assertTrue(dispatchEInkVolumeKey(true, code, KeyEvent.ACTION_DOWN, 0))
                assertTrue(dispatchEInkVolumeKey(true, code, KeyEvent.ACTION_UP, 0))
            }
        } else {
            compose.onNodeWithTag("alignment-reader").performTouchInput {
                click(Offset(width * if (forward) .8f else .2f, height * .5f))
            }
        }
        compose.waitUntil(5_000) { page.get() == expected }
        awaitVisualFrame()
    }

    private fun swipeArticle(forward: Boolean) {
        val expected = identity.value + if (forward) 1 else -1
        compose.onNodeWithTag("alignment-reader").performTouchInput {
            val start = Offset(width * if (forward) .75f else .25f, height * .5f)
            val end = Offset(width * if (forward) .25f else .75f, height * .5f)
            swipe(start, end, 400)
        }
        compose.waitUntil(10_000) { identity.value == expected && page.get() == 1 && count.get() >= 13 }
        compose.waitUntil(10_000) {
            readGeometry()?.let {
                it.getString("title").endsWith(expected.toString()) && it.getBoolean("ready") &&
                    it.getInt("currentPage") == 0 && it.getInt("totalPages") == count.get()
            } ?: false
        }
        awaitVisualFrame()
    }

    private fun awaitFirstPage() {
        compose.waitUntil(15_000) { page.get() == 1 && count.get() >= 13 }
        awaitVisualFrame()
    }

    private fun installOrdinaryImageResponses() {
        val jpeg = instrumentation.context.assets.open("nasa-earthrise.jpg").use { it.readBytes() }
        instrumentation.runOnMainSync {
            val webView = webView()
            val production = webView.webViewClient
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    production.shouldOverrideUrlLoading(view, url)
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    production.shouldOverrideUrlLoading(view, request)
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString()
                    if (url != "$IMAGE_ORIGIN/ordinary.jpg" && url != "$IMAGE_ORIGIN/late.jpg") {
                        return production.shouldInterceptRequest(view, request)
                    }
                    if (url?.endsWith("/late.jpg") == true) {
                        lateRequested.countDown()
                        if (!releaseLateImage.await(120, TimeUnit.SECONDS)) return WebResourceResponse(
                            "image/jpeg", null, ByteArrayInputStream(ByteArray(0)))
                        lateDelivered.countDown()
                    }
                    return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(jpeg))
                }
            }
        }
    }

    private fun record(label: String, screenshot: Boolean = false) {
        compose.waitForIdle()
        val value = geometry().put("label", label).put("nativePage", page.get()).put("nativePageCount", count.get())
        samples.put(value)
        val width = value.getDouble("viewportWidth")
        val padding = value.getDouble("paddingLeft")
        val fragments = value.getJSONArray("paragraphFragments")
        val metadata = value.getJSONArray("metadataFragments")
        val tolerance = 1.25 // CSS px: covers raster/subpixel quantization, not page-by-page drift.
        if (abs(value.getDouble("scrollX")) > tolerance || abs(value.getDouble("scrollY")) > tolerance)
            violations += "$label: root scrolled (${value.getDouble("scrollX")}, ${value.getDouble("scrollY")})"
        if (value.getInt("currentPage") + 1 != page.get() || value.getInt("totalPages") != count.get())
            violations += "$label: native/DOM page state disagrees"
        val images = value.getJSONArray("visibleImages")
        if (fragments.length() == 0 && metadata.length() == 0 &&
            (0 until images.length()).none { images.getJSONObject(it).getInt("naturalWidth") > 0 })
            violations += "$label: no visible article text, metadata or decoded ordinary images"
        // At very large system fonts the title/byline legitimately occupies page one.
        // Its column box still has to satisfy the same margins as ordinary paragraphs.
        val textBoxes = (0 until fragments.length()).map { fragments.getJSONObject(it) } +
            (0 until metadata.length()).map { metadata.getJSONObject(it) }
        for ((index, rect) in textBoxes.withIndex()) {
            val left = rect.getDouble("left")
            val rightMargin = width - rect.getDouble("right")
            if (abs(left - padding) > tolerance || abs(rightMargin - padding) > tolerance) {
                violations += "$label fragment $index: left=$left right=$rightMargin expected=$padding"
                break
            }
        }
        for (index in 0 until images.length()) {
            val image = images.getJSONObject(index)
            if (image.getInt("naturalWidth") > 0 && abs(image.getDouble("left") + image.getDouble("right") - width) > tolerance * 2)
                violations += "$label: ordinary image is not centered in its page"
        }
        writeReport()
        if (screenshot) {
            NativePageDensityCapture.capture(compose, "reader-alignment-$label", "", recordDensity = false,
                contentBoundsOnScreen = ::documentBodyBounds, beforeCaptureFrame = ::awaitVisualFrame)
        }
    }

    private fun documentBodyBounds(): Rect {
        var bounds: Rect? = null
        instrumentation.runOnMainSync {
            val view = webView()
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            // Exclude metadata, native progress/system bars and paper borders. The shared
            // native capture helper waits for committed drawing and rejects a blank body.
            bounds = Rect(origin[0] + view.width * .08f, origin[1] + view.height * .25f,
                origin[0] + view.width * .92f, origin[1] + view.height * .85f)
        }
        return requireNotNull(bounds)
    }

    private fun geometry(): JSONObject = requireNotNull(readGeometry()) { "Reader document is not ready for geometry capture" }

    private fun readGeometry(): JSONObject? {
        val result = AtomicReference<String>()
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView().evaluateJavascript("""
                (function() {
                    if (!document.body || !document.querySelector('h1') || typeof _pageStride === 'undefined') return null;
                    var body = document.body, css = getComputedStyle(body);
                    var width = document.documentElement.getBoundingClientRect().width;
                    function visible(r) { return r.width > 0 && r.height > 0 && r.right > 1 && r.left < width - 1; }
                    function rect(r) { return {left:r.left, right:r.right, top:r.top, bottom:r.bottom, width:r.width}; }
                    var paragraphs = Array.from(document.querySelectorAll('p.alignment-text')).flatMap(function(p) {
                        return Array.from(p.getClientRects()).filter(visible).map(rect);
                    });
                    var metadata = Array.from(document.querySelectorAll('.eink-metadata')).flatMap(function(p) {
                        return Array.from(p.getClientRects()).filter(visible).map(rect);
                    });
                    var images = Array.from(document.images).filter(function(i) { return visible(i.getBoundingClientRect()); }).map(function(i) {
                        var r = rect(i.getBoundingClientRect()); r.naturalWidth = i.naturalWidth; r.complete = i.complete; return r;
                    });
                    var late = document.getElementById('late-image');
                    var ordinary = document.getElementById('ordinary-image');
                    return JSON.stringify({viewportWidth:width, innerWidth:innerWidth, devicePixelRatio:devicePixelRatio,
                        bodyWidth:body.getBoundingClientRect().width, columnWidth:css.columnWidth, columnGap:css.columnGap,
                        paddingLeft:parseFloat(css.paddingLeft), paddingRight:parseFloat(css.paddingRight),
                        scrollX:scrollX, scrollY:scrollY, scrollWidth:body.scrollWidth, stride:_pageStride,
                        currentPage:_currentPage, totalPages:_totalPages, ready:_didFinishInitialPagination, transform:css.transform,
                        fontSize:css.fontSize, title:document.querySelector('h1').textContent,
                        paragraphFragments:paragraphs, metadataFragments:metadata, visibleImages:images,
                        ordinaryImage:{complete:ordinary ? ordinary.complete : false, naturalWidth:ordinary ? ordinary.naturalWidth : 0},
                        lateImage:{complete:late ? late.complete : false, naturalWidth:late ? late.naturalWidth : 0}});
                })()
            """.trimIndent()) { result.set(it); ready.countDown() }
        }
        assertTrue("Reader geometry result must arrive", ready.await(10, TimeUnit.SECONDS))
        // Navigation can briefly expose an empty document, or WebView can return JS null.
        // Polling readiness must retry that state rather than casting JSON null to String.
        return (JSONTokener(requireNotNull(result.get())).nextValue() as? String)?.let { JSONObject(it) }
    }

    private fun awaitVisualFrame() {
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView().postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { ready.countDown() }
            })
        }
        assertTrue("Production WebView must commit a rendered frame", ready.await(10, TimeUnit.SECONDS))
    }

    private fun webView(): WebView {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
        return requireNotNull(find(activity.window.decorView))
    }

    private fun outputDirectory(): File {
        val requested = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        return (if (requested.isNullOrBlank()) File(instrumentation.targetContext.getExternalFilesDir(null), "reader-alignment")
            else File(requested)).apply { check(isDirectory || mkdirs()) }
    }

    private fun writeReport() {
        val configuration = instrumentation.targetContext.resources.configuration
        val metrics = instrumentation.targetContext.resources.displayMetrics
        File(outputDirectory(), "reader-alignment.json").writeText(JSONObject()
            .put("schema", 1).put("screenWidthDp", configuration.screenWidthDp)
            .put("densityDpi", metrics.densityDpi).put("systemFontScale", configuration.fontScale)
            .put("fixtureParagraphs", paragraphCount())
            .put("samples", samples).put("violations", JSONArray(violations)).toString(2))
    }

    private fun fixture(): String = buildString {
        repeat(paragraphCount()) { index ->
            append("<p class=\"alignment-text\">第 ${index + 1} 段：")
            repeat(3) { append("这是普通中文阅读文章，窗外的阳光照亮书页，读者逐页浏览文字和照片。每一页两侧应当保留一致的空白，正文仍然自然排列。") }
            append("</p>")
            if (index == 0) append("<img id=\"ordinary-image\" src=\"$IMAGE_ORIGIN/ordinary.jpg\" alt=\"Earthrise ordinary inline photo\">")
            if (index == 2) append("<img id=\"late-image\" src=\"$IMAGE_ORIGIN/late.jpg\" alt=\"Delayed ordinary inline photo\">")
        }
    }

    // Keep >=13 pages on the widest tablet, but stay below the production 500-page sanity
    // cap on phones at 32px x system-font-1.6 after resizing. The same content is retained
    // throughout each run's font changes, image loads and article swipe round trips.
    private fun paragraphCount(): Int = if (instrumentation.targetContext.resources.configuration.screenWidthDp >= 600) 180 else 100

    companion object { private const val IMAGE_ORIGIN = "https://reader-alignment.invalid" }
}
