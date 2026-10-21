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

/** Separate storage from device presets preserves the existing Auto/Phone/Tablet values. */
enum class ReadingColumns(val persistedValue: Int) {
    Auto(0),
    One(1),
    Two(2);

    companion object {
        fun fromValue(value: Int?): ReadingColumns =
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
 * must behave like a phone when its pane is narrow. Compact newspaper columns are available on
 * phones too, with a full-width lead article. The Phone preset keeps two lanes; Auto/Tablet add three or four only
 * when the measured text budget supports them. Explicit choices apply to both articles and publications; Two falls back to One
 * only when the measured pane or accessibility text size cannot fit readable columns.
 */
fun resolveReadingLayout(
    availableWidthDp: Float,
    fontScale: Float = 1f,
    preset: ReadingLayoutPreset = ReadingLayoutPreset.Auto,
    columnPreference: ReadingColumns = ReadingColumns.Auto,
): ReadingLayoutMetrics {
    val measuredWidth = availableWidthDp.takeIf { it.isFinite() && it > 0f }
    val width = measuredWidth ?: 320f
    val textScale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(1f) ?: 1f
    val horizontalPadding = when {
        width < 360f -> 12f
        width < 600f || preset == ReadingLayoutPreset.Phone -> 16f
        else -> 24f
    }.coerceAtMost(width / 8f)
    val columnSpacing = when {
        width < 360f -> 12f
        width < 600f || preset == ReadingLayoutPreset.Phone -> 16f
        else -> 24f
    }
    val contentWidth = (width - 2f * horizontalPadding).coerceAtLeast(0f)
    val candidateColumnWidth = (contentWidth - columnSpacing) / 2f
    // 320dp phones have 142dp columns, enough for compact type even at 1.2x font scale.
    // Keep modest accessibility adjustments in two columns; only narrow panes or genuinely
    // large text fall back. Article blocks never shrink the user's font scale to make text fit.
    val fitsTwoColumns = measuredWidth != null && width >= 320f &&
        candidateColumnWidth >= 112f * textScale
    val columns = when {
        columnPreference == ReadingColumns.One || !fitsTwoColumns -> 1
        columnPreference == ReadingColumns.Two || preset == ReadingLayoutPreset.Phone -> 2
        // Tablets earn additional editorial lanes from actual pane width and system font scale.
        // Two compact phone columns remain possible, but three/four need roomier body text.
        else -> floor((contentWidth + columnSpacing) / (180f * textScale + columnSpacing))
            .toInt().coerceIn(2, 4)
    }
    val publicationColumns = when {
        columns == 1 -> 1
        columnPreference == ReadingColumns.Two || preset == ReadingLayoutPreset.Phone -> 2
        else -> floor((contentWidth + columnSpacing) / (240f * textScale + columnSpacing))
            .toInt().coerceIn(2, 3)
    }
    val columnWidth = (contentWidth - columnSpacing * (columns - 1)) / columns

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
 * For an article block, pass its actual image-slot width here, rather than resolving pane columns
 * again. Image slots may occupy part of a lead or feature; they do not necessarily span the pane.
 */
fun resolveArticleImageHeightDp(availableWidthDp: Float, isLead: Boolean = false): Float {
    val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 320f
    val preferredHeight = if (isLead) (width / 1.5f).coerceIn(104f, 208f)
    else (width / 1.6f).coerceIn(104f, 184f)
    return preferredHeight.coerceAtMost(width / 1.4f)
}
