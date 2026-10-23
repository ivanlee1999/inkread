package me.ash.reader.infrastructure.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFormattingTest {
    @Test fun defaultsMatchReleasedReaderAppearance() {
        val value = ReaderFormatting.Default
        assertEquals(18, value.fontSize)
        assertEquals(18, value.chineseFontSize)
        assertEquals(1f, value.lineHeight)
        assertEquals(0.5f, value.letterSpacing)
        assertEquals(0f, value.wordSpacing)
        assertEquals(listOf(24, 24, 24, 24), listOf(value.marginLeft, value.marginRight, value.marginTop, value.marginBottom))
        assertEquals(400, value.textWeight)
        assertEquals(1f, value.textDarkness)
        assertEquals(1f, value.paragraphGap)
        assertEquals(0f, value.paragraphIndent)
        assertEquals(1f, value.imageContrast)
        assertEquals(false, value.imageGrayscale)
        assertEquals(value, value.clamped())
    }

    @Test fun scriptSizesClampIndependentlyAndCopiesPreserveTheOtherScript() {
        val format = ReaderFormatting(fontSize = 20, chineseFontSize = 26)
        assertEquals(26, format.copy(fontSize = 22).chineseFontSize)
        assertEquals(20, format.copy(chineseFontSize = 30).fontSize)
        assertEquals(12, format.copy(chineseFontSize = -1).clamped().chineseFontSize)
        assertEquals(48, format.copy(chineseFontSize = Int.MAX_VALUE).clamped().chineseFontSize)
        assertEquals(32, ReaderFormatting(fontSize = 32).chineseFontSize)
    }

    @Test fun validCustomSettingsAreNotRoundedOrReset() {
        val value = ReaderFormatting(
            fontSize = 23, englishFont = 9, chineseFont = 5, lineHeight = 1.73f,
            marginLeft = 17, marginRight = 29, marginTop = 11, marginBottom = 39,
            textDarkness = 0.8f, textWeight = 550, letterSpacing = 0.35f, wordSpacing = 0.15f,
            paragraphGap = 2.1f, paragraphIndent = 1.5f, alignment = ReaderTextAlignment.Justify,
            imageContrast = 1.25f, imageGrayscale = true,
        )
        assertEquals(value, value.clamped())
    }

    @Test fun clampsEveryNumericFieldIndependently() {
        val low = ReaderFormatting(
            fontSize = -1, englishFont = -1, chineseFont = -1, lineHeight = -1f,
            marginLeft = -1, marginRight = 1000, marginTop = Int.MIN_VALUE, marginBottom = Int.MAX_VALUE,
            textDarkness = -1f, textWeight = -1, letterSpacing = -100f, wordSpacing = -100f,
            paragraphGap = -1f, paragraphIndent = -1f, imageContrast = -1f,
        ).clamped()
        assertEquals(12, low.fontSize)
        assertEquals(0, low.englishFont)
        assertEquals(0, low.chineseFont)
        assertEquals(0.8f, low.lineHeight)
        assertEquals(listOf(0, 96, 0, 96), listOf(low.marginLeft, low.marginRight, low.marginTop, low.marginBottom))
        assertEquals(0.5f, low.textDarkness)
        assertEquals(400, low.textWeight)
        assertEquals(-2f, low.letterSpacing)
        assertEquals(-0.25f, low.wordSpacing)
        assertEquals(0f, low.paragraphGap)
        assertEquals(0f, low.paragraphIndent)
        assertEquals(0.5f, low.imageContrast)
        val high = low.copy(
            fontSize = Int.MAX_VALUE, englishFont = 10, chineseFont = 6, lineHeight = 100f,
            textDarkness = 2f, textWeight = 2000, letterSpacing = 100f, wordSpacing = 100f,
            paragraphGap = 100f, paragraphIndent = 100f, imageContrast = 100f,
        ).clamped()
        assertEquals(48, high.fontSize)
        assertEquals(0, high.englishFont)
        assertEquals(0, high.chineseFont)
        assertEquals(2.5f, high.lineHeight)
        assertEquals(1f, high.textDarkness)
        assertEquals(900, high.textWeight)
        assertEquals(8f, high.letterSpacing)
        assertEquals(1f, high.wordSpacing)
        assertEquals(3f, high.paragraphGap)
        assertEquals(3f, high.paragraphIndent)
        assertEquals(2f, high.imageContrast)
    }

    @Test fun nonFiniteValuesBecomeSafeDefaultsRatherThanInvalidCss() {
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY).forEach { invalid ->
            val result = ReaderFormatting(
                lineHeight = invalid, textDarkness = invalid, letterSpacing = invalid,
                wordSpacing = invalid, paragraphGap = invalid, paragraphIndent = invalid,
                imageContrast = invalid,
            ).clamped()
            assertEquals(ReaderFormatting.Default, result)
        }
    }

    @Test fun draftCopiesCannotChangeSnapshotOrDefaults() {
        val snapshot = ReaderFormatting.Default.copy(fontSize = 24)
        val draft = snapshot.copy(fontSize = 30, marginLeft = 40)
        assertNotSame(snapshot, draft)
        assertEquals(24, snapshot.fontSize)
        assertEquals(24, snapshot.marginLeft)
        assertEquals(18, ReaderFormatting.Default.fontSize)
    }

    @Test fun alignmentUsesStableLegacyValuesAndKnownCssOnly() {
        assertEquals(listOf(0, 1, 2, 3), ReaderTextAlignment.entries.map { it.persistedValue })
        ReaderTextAlignment.entries.forEach {
            assertEquals(it, ReaderTextAlignment.fromValue(it.persistedValue))
            assertTrue(it.cssValue in listOf("start", "end", "center", "justify"))
        }
        assertEquals(ReaderTextAlignment.Start, ReaderTextAlignment.fromValue(null))
        assertEquals(ReaderTextAlignment.Start, ReaderTextAlignment.fromValue(Int.MAX_VALUE))
    }
}
