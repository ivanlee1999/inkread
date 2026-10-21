package me.ash.reader.ui.page.home

import android.graphics.Bitmap
import android.graphics.Color
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
    fun capture(compose: ComposeContentTestRule, name: String, viewportTag: String, recordDensity: Boolean = true) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = if (output.isNullOrBlank()) File(context.getExternalFilesDir(null), "newspaper-screenshots") else File(output)
        check(directory.isDirectory || directory.mkdirs())
        val stem = "newspaper-$name-${context.resources.configuration.screenWidthDp}"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
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
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and descendants, useUnmergedTree = true)
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
        val photos = compose.onAllNodes(hasTestTag("article-photo") and descendants, useUnmergedTree = true)
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
        val sources = compose.onAllNodes(SemanticsMatcher("placed source identity") { node ->
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
            tag.startsWith("article-block-") || tag.startsWith("folder-block:") || tag.startsWith("folder-publication:")
        } and descendants, useUnmergedTree = true).fetchSemanticsNodes()
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
            .put("definition", "Union of actual laid-out text-line extents and decoded photograph non-paper pixels. Excludes card rectangles, unused photo reservations, letterboxing, rules, padding, header and controls. Sparse pages may legitimately remain blank.")
        File(directory, "$stem.density.json").writeText(report.toString(2))
    }
}
