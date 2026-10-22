package me.ash.reader.infrastructure.preference

/**
 * A complete, immutable formatting snapshot. Draft copies are never persisted until Apply.
 * Sizes and margins are CSS pixels; paragraph and word spacing are em units.
 * This model deliberately has no Android/Compose dependency.
 */
data class ReaderFormatting(
    val fontSize: Int = 18,
    val englishFont: Int = 0,
    val chineseFont: Int = 0,
    val lineHeight: Float = 1f,
    val marginLeft: Int = 24,
    val marginRight: Int = 24,
    val marginTop: Int = 24,
    val marginBottom: Int = 24,
    val textDarkness: Float = 1f,
    val textWeight: Int = 400,
    val letterSpacing: Float = 0.5f,
    val wordSpacing: Float = 0f,
    val paragraphGap: Float = 1f,
    val paragraphIndent: Float = 0f,
    val alignment: ReaderTextAlignment = ReaderTextAlignment.Start,
    val imageContrast: Float = 1f,
    val imageGrayscale: Boolean = false,
) {
    fun clamped(): ReaderFormatting = copy(
        fontSize = fontSize.coerceIn(FontSizeRange),
        englishFont = englishFont.takeIf { it in EnglishFontRange } ?: Default.englishFont,
        chineseFont = chineseFont.takeIf { it in ChineseFontRange } ?: Default.chineseFont,
        lineHeight = lineHeight.finiteIn(LineHeightRange, Default.lineHeight),
        marginLeft = marginLeft.coerceIn(MarginRange),
        marginRight = marginRight.coerceIn(MarginRange),
        marginTop = marginTop.coerceIn(MarginRange),
        marginBottom = marginBottom.coerceIn(MarginRange),
        textDarkness = textDarkness.finiteIn(TextDarknessRange, Default.textDarkness),
        textWeight = textWeight.coerceIn(TextWeightRange),
        letterSpacing = letterSpacing.finiteIn(LetterSpacingRange, Default.letterSpacing),
        wordSpacing = wordSpacing.finiteIn(WordSpacingRange, Default.wordSpacing),
        paragraphGap = paragraphGap.finiteIn(ParagraphGapRange, Default.paragraphGap),
        paragraphIndent = paragraphIndent.finiteIn(ParagraphIndentRange, Default.paragraphIndent),
        imageContrast = imageContrast.finiteIn(ImageContrastRange, Default.imageContrast),
    )

    companion object {
        val Default = ReaderFormatting()
        val FontSizeRange = 12..48
        val EnglishFontRange = 0..9
        val ChineseFontRange = 0..5
        val LineHeightRange = 0.8f..2.5f
        val MarginRange = 0..96
        val TextDarknessRange = 0.5f..1f
        val TextWeightRange = 400..900
        val LetterSpacingRange = -2f..8f
        val WordSpacingRange = -0.25f..1f
        val ParagraphGapRange = 0f..3f
        val ParagraphIndentRange = 0f..3f
        val ImageContrastRange = 0.5f..2f
    }
}

/** Stable values shared with the existing readingTextAlign preference, not enum ordinals. */
enum class ReaderTextAlignment(val persistedValue: Int, val cssValue: String) {
    Start(0, "start"),
    End(1, "end"),
    Center(2, "center"),
    Justify(3, "justify");

    companion object {
        fun fromValue(value: Int?): ReaderTextAlignment =
            entries.firstOrNull { it.persistedValue == value } ?: Start
    }
}

private fun Float.finiteIn(range: ClosedFloatingPointRange<Float>, fallback: Float): Float =
    if (isFinite()) coerceIn(range) else fallback
