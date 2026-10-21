package me.ash.reader.ui.page.home

import androidx.compose.ui.text.TextLayoutResult

/**
 * Checks actual line/character extents, complete endpoints and height, not paragraph allocation.
 * String Text's semantics reconstructs MultiParagraph with the parent's maxWidth but retains the
 * real shrink-wrapped Text size (ParagraphLayoutCache.slowCreateTextLayoutResultOrNull). Thus
 * hasVisualOverflow can be true for a fully visible 96px line in a 96px Text with a 320px paragraph.
 * Native diagnostics reproduced this for both publication counts and settings titles. Do not
 * drop height, ellipsis, endpoints or glyph bounds when changing this oracle.
 * Source: androidx/androidx compose/foundation/.../text/modifiers/ParagraphLayoutCache.kt
 */
internal fun completeTextLayoutFailure(layout: TextLayoutResult): String? {
    val text = layout.layoutInput.text.text
    if (layout.lineCount == 0) return if (text.isEmpty()) null else "No laid-out text lines"
    if (layout.didOverflowHeight) return "Text exceeds its height or maximum line count"
    if (layout.getLineStart(0) != 0 || layout.getLineEnd(layout.lineCount - 1) != text.length) {
        return "Laid-out lines do not preserve the complete source text"
    }
    val width = layout.size.width.toFloat()
    val height = layout.size.height.toFloat()
    // Only floating-point numerical noise is tolerated, not a pixel of clipped content.
    val epsilon = 0.01f
    fun outside(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        !left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            left < -epsilon || top < -epsilon || right > width + epsilon || bottom > height + epsilon
    for (line in 0 until layout.lineCount) {
        if (layout.isLineEllipsized(line)) return "Line $line is ellipsized"
        if (outside(layout.getLineLeft(line), layout.getLineTop(line),
                layout.getLineRight(line), layout.getLineBottom(line))) return "Line $line crosses the actual Text bounds"
        if (line > 0 && layout.getLineStart(line) != layout.getLineEnd(line - 1)) {
            return "Source text is omitted between lines ${line - 1} and $line"
        }
    }
    for (offset in text.indices) {
        if (text[offset] == '\n' || text[offset] == '\r') continue
        val box = layout.getBoundingBox(offset)
        if (outside(box.left, box.top, box.right, box.bottom)) return "Character at offset $offset crosses the actual Text bounds: $box"
    }
    return null
}
