package me.ash.reader.ui.page.home

import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewTreeObserver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Evidence from the actual production screen, not allocated card/row rectangles. Text uses the
 * laid-out line extents; photos use their actual non-paper pixels, so Fit letterboxing, unloaded
 * reservations, borders, and stretched empty containers cannot improve content coverage.
 * This is an occupancy diagnostic, not a claim that a page of short sources should be 100% ink.
 */
internal object NativePageDensityCapture {
    fun capture(
        compose: ComposeContentTestRule, name: String, viewportTag: String,
        recordDensity: Boolean = true,
        contentBoundsOnScreen: (() -> Rect)? = null,
        beforeCaptureFrame: (() -> Unit)? = null,
        contentRegionsOnScreen: (() -> List<Rect>)? = null,
    ) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = if (output.isNullOrBlank()) File(context.getExternalFilesDir(null), "newspaper-screenshots") else File(output)
        check(directory.isDirectory || directory.mkdirs())
        val stem = "newspaper-$name-${context.resources.configuration.screenWidthDp}"
        // Compose semantics can be ready before SurfaceFlinger has received the first real
        // frame. Wait for committed view drawing, then reject a uniformly blank content region.
        // System bars are outside these Compose-window bounds and cannot satisfy this guard.
        var captured: Bitmap? = null
        for (attempt in 1..3) {
            beforeCaptureFrame?.invoke()
            awaitCommittedFrames(compose)
            val candidate = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val explicitBounds = contentBoundsOnScreen?.invoke()
            val explicitRegions = contentRegionsOnScreen?.invoke()
            // Test-specific body regions stay separate so unrelated ink between them
            // cannot satisfy the same contrast threshold used by a single region.
            val rendered = if (explicitRegions != null) explicitRegions.any { regionHasRenderedContrast(candidate, it) }
                else if (explicitBounds == null) containsRenderedContent(compose, candidate)
                else regionHasRenderedContrast(candidate, explicitBounds)
            if (rendered) {
                captured = candidate
                break
            }
            candidate.recycle()
        }
        val bitmap = checkNotNull(captured) { "$name stayed blank inside its real content bounds after three committed frames" }
        val screenshot = File(directory, "$stem.png")
        screenshot.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(screenshot.length() > 0)
        // A modal screenshot is useful visual evidence but is not an unobscured reading page.
        if (!recordDensity) return

        val viewport = compose.onNodeWithTag(viewportTag).fetchSemanticsNode()
        val bounds = viewport.boundsInRoot
        val view = (viewport.root as ViewRootForTest).view
        val origin = IntArray(2)
        compose.runOnUiThread { view.getLocationOnScreen(origin) }
        val left = floor(bounds.left + origin[0]).toInt().coerceIn(0, bitmap.width)
        val top = floor(bounds.top + origin[1]).toInt().coerceIn(0, bitmap.height)
        val right = ceil(bounds.right + origin[0]).toInt().coerceIn(left, bitmap.width)
        val bottom = ceil(bounds.bottom + origin[1]).toInt().coerceIn(top, bitmap.height)
        val width = right - left
        val height = bottom - top
        check(width > 0 && height > 0)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        val textMask = BitSet(pixels.size)
        val photoMask = BitSet(pixels.size)
        val textLines = JSONArray()
        val photographs = JSONArray()
        var renderedLineCount = 0
        val observedFontScales = linkedSetOf<Float>()

        fun paper(pixel: Int): Boolean = Color.red(pixel) >= 245 && Color.green(pixel) >= 245 && Color.blue(pixel) >= 245
        fun rect(boundsInRoot: Rect): IntArray = intArrayOf(
            floor(boundsInRoot.left + origin[0] - left).toInt().coerceIn(0, width),
            floor(boundsInRoot.top + origin[1] - top).toInt().coerceIn(0, height),
            ceil(boundsInRoot.right + origin[0] - left).toInt().coerceIn(0, width),
            ceil(boundsInRoot.bottom + origin[1] - top).toInt().coerceIn(0, height),
        )
        fun putRectangle(mask: BitSet, boundsInRoot: Rect) {
            val r = rect(boundsInRoot)
            if (r[2] <= r[0] || r[3] <= r[1]) return
            for (y in r[1] until r[3]) mask.set(y * width + r[0], y * width + r[2])
        }
        val descendants = hasAnyAncestor(hasTestTag(viewportTag))
        val placedSource = SemanticsMatcher("placed source identity") { node ->
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
            tag.startsWith("article-block-") || tag.startsWith("folder-block:") || tag.startsWith("folder-publication:")
        }
        // SubcomposeLayout can retain semantics for measurement-only Text nodes. Only a real
        // placed source block carries these tags; viewport ancestry alone is insufficient.
        val inPlacedSource = hasAnyAncestor(placedSource)
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and descendants and inPlacedSource,
            useUnmergedTree = true)
        texts.fetchSemanticsNodes().indices.forEach { index ->
            val node = texts[index].fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            texts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "text" }
            layouts.forEach { layout ->
                observedFontScales += layout.layoutInput.density.fontScale
                for (line in 0 until layout.lineCount) {
                    val lineBounds = Rect(
                        node.boundsInRoot.left + min(layout.getLineLeft(line), layout.getLineRight(line)),
                        node.boundsInRoot.top + layout.getLineTop(line),
                        node.boundsInRoot.left + max(layout.getLineLeft(line), layout.getLineRight(line)),
                        node.boundsInRoot.top + layout.getLineBottom(line),
                    ).intersect(node.boundsInRoot).intersect(bounds)
                    if (lineBounds.width <= 0f || lineBounds.height <= 0f) continue
                    renderedLineCount++
                    putRectangle(textMask, lineBounds)
                    textLines.put(JSONObject().put("tag", tag).put("line", line)
                        .put("ellipsis", layout.isLineEllipsized(line))
                        .put("boundsInViewportPx", JSONArray(rect(lineBounds).toList())))
                }
            }
        }
        val photos = compose.onAllNodes(hasTestTag("article-photo") and descendants and inPlacedSource, useUnmergedTree = true)
        photos.fetchSemanticsNodes().forEach { node ->
            val r = rect(node.boundsInRoot.intersect(bounds))
            var nonPaper = 0
            var fixtureBlue = 0
            for (y in r[1] until r[3]) for (x in r[0] until r[2]) {
                val pixel = pixels[y * width + x]
                if (!paper(pixel)) nonPaper++
                // The deterministic asset is the real NASA Earthrise photograph. Its blue Earth
                // distinguishes decoded content from an empty/failed/loading image reservation.
                if (Color.blue(pixel) > Color.red(pixel) + 8 && Color.blue(pixel) > Color.green(pixel) + 2) fixtureBlue++
            }
            val loaded = fixtureBlue > 20
            if (loaded) for (y in r[1] until r[3]) for (x in r[0] until r[2]) {
                if (!paper(pixels[y * width + x])) photoMask.set(y * width + x)
            }
            photographs.put(JSONObject().put("boundsInViewportPx", JSONArray(r.toList()))
                .put("decodedFixturePhoto", loaded).put("nonPaperPixels", nonPaper))
        }
        val contentMask = textMask.clone() as BitSet
        contentMask.or(photoMask)
        var textInkPixels = 0
        var index = textMask.nextSetBit(0)
        while (index >= 0) {
            if (!paper(pixels[index])) textInkPixels++
            index = textMask.nextSetBit(index + 1)
        }
        val contentBottom = if (contentMask.isEmpty) 0 else (contentMask.length() - 1) / width + 1
        val sources = compose.onAllNodes(placedSource and descendants, useUnmergedTree = true).fetchSemanticsNodes()
        // Article: <=30 excerpt +5 headline +source/time lines. Folder: <=35 digest
        // names +3 title +count/footer lines. Leave modest headroom, not hundreds of probes.
        check(renderedLineCount <= sources.size * 48) {
            "$name contains $renderedLineCount text lines for ${sources.size} placed sources; measurement-only semantics may have leaked"
        }
        val range = if (viewport.config.contains(SemanticsProperties.VerticalScrollAxisRange))
            viewport.config[SemanticsProperties.VerticalScrollAxisRange] else null
        val report = JSONObject()
            .put("schema", 1).put("screen", name).put("viewportTag", viewportTag)
            .put("screenWidthDp", context.resources.configuration.screenWidthDp)
            .put("fontScales", JSONArray(observedFontScales.toList()))
            .put("nativeScreenPx", JSONArray(listOf(bitmap.width, bitmap.height)))
            .put("viewportScreenPx", JSONArray(listOf(left, top, right, bottom)))
            .put("pageIndex", range?.value()?.toInt()).put("pageCount", range?.maxValue()?.toInt()?.plus(1))
            .put("viewportAreaPx", width * height)
            .put("textLineAreaPx", textMask.cardinality()).put("textInkPixels", textInkPixels)
            .put("decodedPhotographPixels", photoMask.cardinality())
            .put("contentAreaPx", contentMask.cardinality())
            .put("contentCoverage", contentMask.cardinality().toDouble() / pixels.size)
            .put("contentBottomGapPx", height - contentBottom)
            .put("rawNonPaperPixels", pixels.count { !paper(it) })
            .put("renderedTextLines", renderedLineCount)
            .put("sourceIds", JSONArray(sources.map { it.config[SemanticsProperties.TestTag] }))
            .put("textLines", textLines).put("photographs", photographs)
            .put("definition", "Union of actual laid-out text-line extents and decoded photograph non-paper pixels. Includes only tagged placed source blocks. Excludes measurement-only nodes, date furniture, card rectangles, unused photo reservations, letterboxing, rules, padding, header and controls. Sparse pages may legitimately remain blank.")
        File(directory, "$stem.density.json").writeText(report.toString(2))
    }
    private fun awaitCommittedFrames(compose: ComposeContentTestRule) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // A Compose host containing only a Dialog has no drawable child bounds. Invalidating
        // that empty host need not schedule a frame, even though its activity window is visible.
        // Commit one frame per real window (including the dialog), not per Compose child.
        val views = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .map { (it.root as ViewRootForTest).view.rootView }.distinct()
        check(views.isNotEmpty())
        val committed = CountDownLatch(views.size)
        compose.runOnUiThread {
            views.forEach { view ->
                check(view.isAttachedToWindow && view.isShown) { "Capture host is not visibly attached" }
                if (view.isHardwareAccelerated) {
                    view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                } else {
                    val listener = object : ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            view.post {
                                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                                committed.countDown()
                            }
                        }
                    }
                    view.viewTreeObserver.addOnDrawListener(listener)
                }
                view.invalidate()
            }
        }
        check(committed.await(5, TimeUnit.SECONDS)) { "Native window did not commit its requested capture frame" }
        instrumentation.waitForIdleSync()
    }

    private fun containsRenderedContent(compose: ComposeContentTestRule, bitmap: Bitmap): Boolean {
        val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { node ->
                node.boundsInRoot.width > 1f && node.boundsInRoot.height > 1f &&
                    node.config[SemanticsProperties.Text].any { it.text.isNotBlank() }
            }
        val nodes = textNodes.ifEmpty { compose.onAllNodes(isRoot()).fetchSemanticsNodes() }
        return nodes.any { node ->
            val view = (node.root as ViewRootForTest).view
            val origin = IntArray(2)
            compose.runOnUiThread { view.getLocationOnScreen(origin) }
            val bounds = node.boundsInRoot
            // A WebView has no Compose Text children. In that case exclude the outer edge too,
            // so a navigation/system boundary line cannot masquerade as a rendered document.
            val inset = if (textNodes.isEmpty()) 16 else 0
            regionHasRenderedContrast(bitmap, Rect(
                bounds.left + origin[0] + inset, bounds.top + origin[1] + inset,
                bounds.right + origin[0] - inset, bounds.bottom + origin[1] - inset,
            ))
        }
    }

    private fun regionHasRenderedContrast(bitmap: Bitmap, bounds: Rect): Boolean {
        val left = ceil(bounds.left).toInt().coerceIn(0, bitmap.width)
        val top = ceil(bounds.top).toInt().coerceIn(0, bitmap.height)
        val right = floor(bounds.right).toInt().coerceIn(left, bitmap.width)
        val bottom = floor(bounds.bottom).toInt().coerceIn(top, bitmap.height)
        if (right <= left || bottom <= top) return false
        val histogram = IntArray(256)
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val value = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            histogram[value]++
        }
        val lowest = histogram.indexOfFirst { it > 0 }
        val highest = histogram.indexOfLast { it > 0 }
        val contrast = highest - lowest
        if (contrast < 48) return false
        // Relative contrast works for both white paper and dark settings. Neither system bars
        // nor a uniform panel can pass because callers supply actual interior content bounds.
        val darker = (lowest..lowest + contrast / 3).sumOf { histogram[it] }
        val lighter = (highest - contrast / 3..highest).sumOf { histogram[it] }
        return darker >= 12 && lighter >= 12
    }
}
