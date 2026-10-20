package me.ash.reader.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLayoutTest {
    private val widths = listOf(320f, 400f, 600f, 800f)

    @Test fun autoUsesLogicalPaneWidthRatherThanDeviceIdentity() {
        assertEquals(listOf(1, 1, 1, 2), widths.map { resolveReadingLayout(it).columns })
        assertEquals(listOf(1, 1, 2, 2), widths.map { resolveReadingLayout(it).publicationColumns })
    }

    @Test fun phoneAlwaysUsesOneColumnEvenOnWideDisplays() {
        (widths + 1600f).forEach { width ->
            val layout = resolveReadingLayout(width, preset = ReadingLayoutPreset.Phone)
            assertEquals(1, layout.columns)
            assertEquals(1, layout.publicationColumns)
        }
    }

    @Test fun tabletPrefersColumnsEarlierButNeverOverridesLegibility() {
        assertEquals(1, resolveReadingLayout(680f, preset = ReadingLayoutPreset.Auto).columns)
        assertEquals(2, resolveReadingLayout(680f, preset = ReadingLayoutPreset.Tablet).columns)
        assertEquals(listOf(1, 1, 1, 2), widths.map {
            resolveReadingLayout(it, preset = ReadingLayoutPreset.Tablet).columns
        })
    }

    @Test fun largeFontsCollapseColumnsInBothAdaptivePresets() {
        listOf(ReadingLayoutPreset.Auto, ReadingLayoutPreset.Tablet).forEach { preset ->
            assertEquals(listOf(1, 1, 1, 1), widths.map {
                resolveReadingLayout(it, fontScale = 1.5f, preset = preset).columns
            })
            assertEquals(1, resolveReadingLayout(800f, fontScale = 2f, preset = preset).publicationColumns)
            assertEquals(2, resolveReadingLayout(1200f, fontScale = 1.5f, preset = preset).columns)
        }
    }

    @Test fun splitScreenAndRotationRecomputeWithoutRememberedDeviceClass() {
        val columns = listOf(800f, 320f, 600f, 800f).map {
            resolveReadingLayout(it, preset = ReadingLayoutPreset.Tablet).columns
        }
        assertEquals(listOf(2, 1, 1, 2), columns)
    }

    @Test fun publicationColumnsCapAtThreeAndRespectScaledMinimumWidth() {
        assertEquals(3, resolveReadingLayout(900f).publicationColumns)
        assertEquals(3, resolveReadingLayout(2400f).publicationColumns)
        ReadingLayoutPreset.entries.forEach { preset ->
            listOf(1f, 1.3f, 1.5f, 2f, 3f).forEach { fontScale ->
                (240..1600 step 8).forEach { width ->
                    val layout = resolveReadingLayout(width.toFloat(), fontScale, preset)
                    val contentWidth = width - layout.horizontalPaddingDp * 2
                    if (layout.columns > 1) {
                        assertTrue((contentWidth - layout.columnSpacingDp) / 2 >= 300f * fontScale)
                    }
                    if (layout.publicationColumns > 1) {
                        val spacing = layout.columnSpacingDp * (layout.publicationColumns - 1)
                        assertTrue((contentWidth - spacing) / layout.publicationColumns >= 240f * fontScale)
                    }
                }
            }
        }
    }

    @Test fun invalidConstraintsSafelyFallBackToFiniteSingleColumn() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { width ->
            val layout = resolveReadingLayout(width, preset = ReadingLayoutPreset.Tablet)
            assertEquals(1, layout.columns)
            assertEquals(1, layout.publicationColumns)
            assertTrue(layout.imageHeightDp.isFinite())
            assertTrue(layout.leadImageHeightDp.isFinite())
        }
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 0.5f).forEach { fontScale ->
            assertEquals(resolveReadingLayout(800f), resolveReadingLayout(800f, fontScale))
        }
    }

    @Test fun imagesUseActualSlotWidthWithoutResolvingColumnsAgain() {
        assertEquals(200f, resolveArticleImageHeightDp(320f), 0.01f)
        assertEquals(200f, resolveArticleImageHeightDp(400f, isLead = true), 0.01f)
        assertEquals(240f, resolveArticleImageHeightDp(1200f), 0.01f)
        assertEquals(340f, resolveArticleImageHeightDp(1200f, isLead = true), 0.01f)
        listOf(false, true).forEach { isLead ->
            assertTrue(resolveArticleImageHeightDp(100f, isLead) <= 100f / 1.4f)
        }
    }

    @Test fun presetValuesAreStableAndUnknownValuesFallBackToAuto() {
        assertEquals(0, ReadingLayoutPreset.Auto.persistedValue)
        assertEquals(1, ReadingLayoutPreset.Phone.persistedValue)
        assertEquals(2, ReadingLayoutPreset.Tablet.persistedValue)
        ReadingLayoutPreset.entries.forEach {
            assertEquals(it, ReadingLayoutPreset.fromValue(it.persistedValue))
        }
        listOf(null, -1, 3, Int.MAX_VALUE).forEach {
            assertEquals(ReadingLayoutPreset.Auto, ReadingLayoutPreset.fromValue(it))
        }
    }
}
