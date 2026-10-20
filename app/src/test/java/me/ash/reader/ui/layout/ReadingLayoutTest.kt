package me.ash.reader.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLayoutTest {
    private val widths = listOf(320f, 400f, 600f, 800f)

    @Test fun autoUsesNewspaperColumnsOnPhonesAndWidePanes() {
        assertEquals(listOf(2, 2, 2, 2), widths.map { resolveReadingLayout(it).columns })
        assertEquals(listOf(2, 2, 2, 2), widths.map { resolveReadingLayout(it).publicationColumns })
    }

    @Test fun phonePresetNoLongerForcesOneColumn() {
        (widths + 1600f).forEach { width ->
            val layout = resolveReadingLayout(width, preset = ReadingLayoutPreset.Phone)
            assertEquals(2, layout.columns)
            assertEquals(2, layout.publicationColumns)
        }
    }

    @Test fun explicitOneColumnAppliesToBothGridsInEveryPreset() {
        ReadingLayoutPreset.entries.forEach { preset ->
            (widths + 1600f).forEach { width ->
                val layout = resolveReadingLayout(width, preset = preset, columnPreference = ReadingColumns.One)
                assertEquals(1, layout.columns)
                assertEquals(1, layout.publicationColumns)
            }
        }
    }

    @Test fun explicitTwoColumnsAppliesOnPhonesAndCapsWidePublicationGrids() {
        ReadingLayoutPreset.entries.forEach { preset ->
            (widths + 1600f).forEach { width ->
                val layout = resolveReadingLayout(width, preset = preset, columnPreference = ReadingColumns.Two)
                assertEquals(2, layout.columns)
                assertEquals(2, layout.publicationColumns)
            }
        }
    }

    @Test fun compactPhoneSpacingDoesNotChangeStoredDevicePresetMeaning() {
        val phone = resolveReadingLayout(800f, preset = ReadingLayoutPreset.Phone)
        val tablet = resolveReadingLayout(800f, preset = ReadingLayoutPreset.Tablet)
        assertTrue(phone.horizontalPaddingDp < tablet.horizontalPaddingDp)
        assertTrue(phone.columnSpacingDp < tablet.columnSpacingDp)
        ReadingLayoutPreset.entries.forEach { preset ->
            assertEquals(2, resolveReadingLayout(320f, preset = preset).columns)
            assertEquals(2, resolveReadingLayout(400f, preset = preset).columns)
        }
    }

    @Test fun moderateFontScalingKeepsNewspaperColumnsOnPhones() {
        ReadingLayoutPreset.entries.forEach { preset ->
            listOf(ReadingColumns.Auto, ReadingColumns.Two).forEach { columns ->
                listOf(320f to 1.2f, 320f to 1.25f, 400f to 1.3f, 400f to 1.5f).forEach { (width, scale) ->
                    val layout = resolveReadingLayout(width, scale, preset, columns)
                    assertEquals(2, layout.columns)
                    assertEquals(2, layout.publicationColumns)
                }
            }
        }
    }

    @Test fun largeFontsCollapsePhoneColumnsWithoutShrinkingText() {
        ReadingLayoutPreset.entries.forEach { preset ->
            listOf(ReadingColumns.Auto, ReadingColumns.Two).forEach { columns ->
                listOf(320f, 400f).forEach { width ->
                    val layout = resolveReadingLayout(width, 1.6f, preset, columns)
                    assertEquals(1, layout.columns)
                    assertEquals(1, layout.publicationColumns)
                }
                assertEquals(2, resolveReadingLayout(800f, 2f, preset, columns).columns)
                assertEquals(1, resolveReadingLayout(800f, 3.5f, preset, columns).columns)
            }
        }
    }

    @Test fun narrowSplitScreenAndRotationRecomputeWithoutRememberedDeviceClass() {
        val columns = listOf(800f, 280f, 320f, 400f, 800f).map {
            resolveReadingLayout(it, preset = ReadingLayoutPreset.Tablet).columns
        }
        assertEquals(listOf(2, 1, 2, 2, 2), columns)
        assertEquals(1, resolveReadingLayout(319f, columnPreference = ReadingColumns.Two).columns)
        assertEquals(2, resolveReadingLayout(320f, columnPreference = ReadingColumns.Two).columns)
    }

    @Test fun phoneSlotsMatchResolvedPaddingGuttersAndImageWidths() {
        val smallPhone = resolveReadingLayout(320f)
        val phone = resolveReadingLayout(400f)
        assertEquals(12f, smallPhone.horizontalPaddingDp, 0.01f)
        assertEquals(12f, smallPhone.columnSpacingDp, 0.01f)
        assertEquals(resolveArticleImageHeightDp(142f), smallPhone.imageHeightDp, 0.01f)
        assertEquals(resolveArticleImageHeightDp(296f, true), smallPhone.leadImageHeightDp, 0.01f)
        assertEquals(resolveArticleImageHeightDp(176f), phone.imageHeightDp, 0.01f)
        assertEquals(resolveArticleImageHeightDp(368f, true), phone.leadImageHeightDp, 0.01f)
        assertTrue(smallPhone.leadImageHeightDp > smallPhone.imageHeightDp)
    }

    @Test fun autoPublicationColumnsCapAtThreeWithRoomierTextBudget() {
        assertEquals(3, resolveReadingLayout(900f).publicationColumns)
        assertEquals(3, resolveReadingLayout(2400f).publicationColumns)
        assertEquals(2, resolveReadingLayout(900f, fontScale = 1.5f).publicationColumns)
        ReadingLayoutPreset.entries.forEach { preset ->
            ReadingColumns.entries.forEach { choice ->
                listOf(1f, 1.3f, 1.5f, 2f, 3f).forEach { fontScale ->
                    (120..1600 step 8).forEach { width ->
                        val layout = resolveReadingLayout(width.toFloat(), fontScale, preset, choice)
                        val contentWidth = width - layout.horizontalPaddingDp * 2
                        if (layout.columns > 1) {
                            assertTrue(width >= 320)
                            assertTrue((contentWidth - layout.columnSpacingDp) / 2 >= 112f * fontScale)
                        }
                        if (layout.publicationColumns > 1) {
                            val spacing = layout.columnSpacingDp * (layout.publicationColumns - 1)
                            val minimum = if (layout.publicationColumns == 3) 240f else 112f
                            assertTrue((contentWidth - spacing) / layout.publicationColumns >= minimum * fontScale)
                        }
                    }
                }
            }
        }
    }

    @Test fun invalidConstraintsSafelyFallBackToFiniteSingleColumn() {
        ReadingColumns.entries.forEach { choice ->
            listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { width ->
                val layout = resolveReadingLayout(width, preset = ReadingLayoutPreset.Tablet, columnPreference = choice)
                assertEquals(1, layout.columns)
                assertEquals(1, layout.publicationColumns)
                assertTrue(layout.imageHeightDp.isFinite())
                assertTrue(layout.leadImageHeightDp.isFinite())
            }
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

    @Test fun columnValuesAreIndependentStableAndUnknownValuesFallBackToAuto() {
        assertEquals(0, ReadingColumns.Auto.persistedValue)
        assertEquals(1, ReadingColumns.One.persistedValue)
        assertEquals(2, ReadingColumns.Two.persistedValue)
        ReadingColumns.entries.forEach {
            assertEquals(it, ReadingColumns.fromValue(it.persistedValue))
        }
        listOf(null, -1, 3, Int.MAX_VALUE).forEach {
            assertEquals(ReadingColumns.Auto, ReadingColumns.fromValue(it))
        }
    }
}
