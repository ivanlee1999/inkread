package me.ash.reader.ui.layout

import kotlin.math.floor

/** Stable preference values. Never persist enum ordinals. */
enum class ReadingLayoutPreset(val persistedValue: Int) {
    Auto(0),
    Phone(1),
    Tablet(2);

    companion object {
        fun fromValue(value: Int?): ReadingLayoutPreset =
            entries.firstOrNull { it.persistedValue == value } ?: Auto
    }
}

/** Dimensions are logical dp, independent of physical screen pixels and device model. */
data class ReadingLayoutMetrics(
    val columns: Int,
    val publicationColumns: Int,
    val horizontalPaddingDp: Float,
    val columnSpacingDp: Float,
    val imageHeightDp: Float,
    val leadImageHeightDp: Float,
)

/**
 * Resolve against the measured content pane, not the whole display. A tablet in split-screen
 * must behave like a phone when its pane is narrow. Font scaling is part of the column budget,
 * so selecting Tablet is a preference, never permission to squeeze unreadably small columns.
 * Invalid/unmeasured constraints safely fall back to one column.
 */
fun resolveReadingLayout(
    availableWidthDp: Float,
    fontScale: Float = 1f,
    preset: ReadingLayoutPreset = ReadingLayoutPreset.Auto,
): ReadingLayoutMetrics {
    val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 320f
    val textScale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(1f) ?: 1f
    val horizontalPadding = when {
        width < 360f -> 12f
        width < 600f -> 16f
        else -> 24f
    }.coerceAtMost(width / 8f)
    val columnSpacing = if (width >= 600f) 24f else 16f
    val contentWidth = (width - 2f * horizontalPadding).coerceAtLeast(0f)
    val candidateColumnWidth = (contentWidth - columnSpacing) / 2f
    val presetThreshold = when (preset) {
        ReadingLayoutPreset.Auto -> 720f
        ReadingLayoutPreset.Phone -> Float.POSITIVE_INFINITY
        ReadingLayoutPreset.Tablet -> 600f
    }
    val columns = if (width >= presetThreshold && candidateColumnWidth >= 300f * textScale) 2 else 1
    val publicationColumns = if (preset == ReadingLayoutPreset.Phone) 1 else {
        floor((contentWidth + columnSpacing) / (240f * textScale + columnSpacing))
            .toInt().coerceIn(1, 3)
    }
    val columnWidth = if (columns == 2) candidateColumnWidth else contentWidth

    return ReadingLayoutMetrics(
        columns = columns,
        publicationColumns = publicationColumns,
        horizontalPaddingDp = horizontalPadding,
        columnSpacingDp = columnSpacing,
        imageHeightDp = resolveArticleImageHeightDp(columnWidth),
        leadImageHeightDp = resolveArticleImageHeightDp(contentWidth, isLead = true),
    )
}

/**
 * For a leaf card, pass its actual image-slot width here, rather than resolving pane columns
 * again. Lead images span the pane; supporting images span just their own column.
 */
fun resolveArticleImageHeightDp(availableWidthDp: Float, isLead: Boolean = false): Float {
    val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 320f
    val preferredHeight = if (isLead) (width / 2f).coerceIn(176f, 340f)
    else (width / 1.6f).coerceIn(144f, 240f)
    return preferredHeight.coerceAtMost(width / 1.4f)
}
