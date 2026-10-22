package me.ash.reader.infrastructure.preference

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.PreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReaderFormattingPreferenceTest {
    @Test fun missingNewSettingsPreserveExistingFontsAndSpacingWithoutWritingMigration() {
        val preferences = preferencesOf(
            intPreferencesKey(DataStoreKey.einkFontSize) to 28,
            intPreferencesKey(DataStoreKey.einkEnglishFont) to 7,
            intPreferencesKey(DataStoreKey.einkChineseFont) to 3,
            floatPreferencesKey(DataStoreKey.readingTextLineHeight) to 1.7f,
            floatPreferencesKey(DataStoreKey.readingTextLetterSpacing) to 0.8f,
            floatPreferencesKey(DataStoreKey.einkWordSpacing) to 0.2f,
            intPreferencesKey(DataStoreKey.readingTextHorizontalPadding) to 36,
        )
        val before = preferences.asMap().toMap()
        val format = ReaderFormattingPreference.fromPreferences(preferences)
        assertEquals(28, format.fontSize)
        assertEquals(7, format.englishFont)
        assertEquals(3, format.chineseFont)
        assertEquals(1.7f, format.lineHeight)
        assertEquals(0.8f, format.letterSpacing)
        assertEquals(0.2f, format.wordSpacing)
        assertEquals(listOf(36, 36, 36, 36), format.margins())
        assertEquals(ReaderFormatting.Default.textWeight, format.textWeight)
        assertEquals(before, preferences.asMap())
        assertNull(preferences[intPreferencesKey(DataStoreKey.einkMarginLeft)])
    }

    @Test fun eachMissingMarginIndependentlyInheritsLegacyPadding() {
        val preferences = preferencesOf(
            intPreferencesKey(DataStoreKey.readingTextHorizontalPadding) to 31,
            intPreferencesKey(DataStoreKey.einkMarginLeft) to 10,
            intPreferencesKey(DataStoreKey.einkMarginBottom) to 42,
        )
        assertEquals(listOf(10, 31, 31, 42), ReaderFormattingPreference.fromPreferences(preferences).margins())
    }

    @Test fun defaultsAndCatalogBoundsMatchExistingPreferenceCatalogs() {
        assertEquals(ReaderFormatting.Default, ReaderFormattingPreference.fromPreferences(emptyPreferences()))
        assertEquals(EInkFontSizePreference.default, ReaderFormatting.Default.fontSize)
        assertEquals(EInkEnglishFontPreference.values, ReaderFormatting.EnglishFontRange.toList())
        assertEquals(EInkChineseFontPreference.values, ReaderFormatting.ChineseFontRange.toList())
        assertEquals(ReadingTextLineHeightPreference.default, ReaderFormatting.Default.lineHeight)
        assertEquals(ReadingTextHorizontalPaddingPreference.default, ReaderFormatting.Default.marginLeft)
        assertEquals(ReadingTextLetterSpacingPreference.default, ReaderFormatting.Default.letterSpacing)
        assertEquals(EInkWordSpacingPreference.default, ReaderFormatting.Default.wordSpacing)
    }

    @Test fun applyIsOneCompleteTransactionAndPreservesUnrelatedAndLegacySettings() = runBlocking {
        val unrelated = intPreferencesKey("unrelated")
        val oldBold = booleanPreferencesKey(DataStoreKey.readingTextBold)
        val oldPadding = intPreferencesKey(DataStoreKey.readingTextHorizontalPadding)
        val original = preferencesOf(unrelated to 81, oldBold to true, oldPadding to 27)
        val store = RecordingDataStore(original)
        val format = ReaderFormatting(
            fontSize = 30, englishFont = 6, chineseFont = 4, lineHeight = 1.9f,
            marginLeft = 12, marginRight = 40, marginTop = 18, marginBottom = 30,
            textDarkness = 0.7f, textWeight = 600, letterSpacing = 1.25f, wordSpacing = 0.35f,
            paragraphGap = 1.8f, paragraphIndent = 2f, alignment = ReaderTextAlignment.Justify,
            imageContrast = 1.5f, imageGrayscale = true,
        )
        assertEquals(format, store.saveReaderFormatting(format))
        assertEquals(1, store.updateCalls)
        assertEquals(1, store.committed.size)
        assertEquals(format, ReaderFormattingPreference.fromPreferences(store.current))
        assertEquals(81, store.current[unrelated])
        assertEquals(true, store.current[oldBold])
        assertEquals(27, store.current[oldPadding])
        assertEquals(original, store.snapshotsBeforeUpdate.single())
        assertNull(original[intPreferencesKey(DataStoreKey.einkMarginLeft)])
    }

    @Test fun symmetricApplyCanUpdateLegacyHorizontalSettingWithoutUsingVerticalMargin() = runBlocking {
        val store = RecordingDataStore(preferencesOf(
            intPreferencesKey(DataStoreKey.readingTextHorizontalPadding) to 27,
        ))
        store.saveReaderFormatting(ReaderFormatting(marginLeft = 18, marginRight = 18, marginTop = 45, marginBottom = 36))
        assertEquals(18, store.current[intPreferencesKey(DataStoreKey.readingTextHorizontalPadding)])
        assertEquals(listOf(18, 18, 45, 36), ReaderFormattingPreference.fromPreferences(store.current).margins())
    }

    @Test fun legacyHorizontalSettingUpdatesBothSidesWithoutChangingExistingVerticalInsets() = runBlocking {
        val store = RecordingDataStore(preferencesOf(
            intPreferencesKey(DataStoreKey.readingTextHorizontalPadding) to 27,
            intPreferencesKey(DataStoreKey.einkMarginLeft) to 9,
            intPreferencesKey(DataStoreKey.einkMarginRight) to 40,
            intPreferencesKey(DataStoreKey.einkMarginBottom) to 32,
        ))
        store.saveReaderHorizontalPadding(19)
        assertEquals(1, store.updateCalls)
        assertEquals(listOf(19, 19, 27, 32), ReaderFormattingPreference.fromPreferences(store.current).margins())
        assertEquals(19, store.current[intPreferencesKey(DataStoreKey.readingTextHorizontalPadding)])
    }

    @Test fun legacyHorizontalSettingKeepsItsOriginalRangeForTheScrollingReader() = runBlocking {
        val store = RecordingDataStore()
        store.saveReaderHorizontalPadding(120)
        assertEquals(120, store.current[intPreferencesKey(DataStoreKey.readingTextHorizontalPadding)])
        assertEquals(listOf(96, 96, 24, 24), ReaderFormattingPreference.fromPreferences(store.current).margins())
    }

    @Test fun legacyBoldDoesNotBecomeEinkWeightAndIsNeverOverwritten() = runBlocking {
        val key = booleanPreferencesKey(DataStoreKey.readingTextBold)
        val store = RecordingDataStore(preferencesOf(key to true))
        assertEquals(400, ReaderFormattingPreference.fromPreferences(store.current).textWeight)
        store.saveReaderFormatting(ReaderFormatting(textWeight = 800))
        assertEquals(true, store.current[key])
        assertEquals(800, ReaderFormattingPreference.fromPreferences(store.current).textWeight)
    }

    @Test fun savesAndReadsOnlyClampedValues() = runBlocking {
        val store = RecordingDataStore()
        val invalid = ReaderFormatting(fontSize = 100, lineHeight = Float.NaN, englishFont = 500, marginLeft = -20, textDarkness = 2f)
        val saved = store.saveReaderFormatting(invalid)
        assertEquals(invalid.clamped(), saved)
        assertEquals(saved, ReaderFormattingPreference.fromPreferences(store.current))
        assertEquals(48, store.current[intPreferencesKey(DataStoreKey.einkFontSize)])
        assertEquals(1f, store.current[floatPreferencesKey(DataStoreKey.readingTextLineHeight)])
        assertFalse(store.current.asMap().values.any { it is Float && !it.isFinite() })
    }

    @Test fun failedApplyDoesNotPublishPartialSettingsOrPretendSuccess() = runBlocking {
        val original = preferencesOf(intPreferencesKey(DataStoreKey.einkFontSize) to 28)
        val store = RecordingDataStore(original, failCommit = true)
        var failed = false
        try {
            store.saveReaderFormatting(ReaderFormatting(fontSize = 36, marginLeft = 18))
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(1, store.updateCalls)
        assertEquals(0, store.committed.size)
        assertEquals(original, store.current)
    }

    @Test fun allNewKeysAreRegisteredForTypedAccessAndBackupRoundTrip() {
        val intNames = listOf(DataStoreKey.einkMarginLeft, DataStoreKey.einkMarginRight, DataStoreKey.einkMarginTop, DataStoreKey.einkMarginBottom, DataStoreKey.einkTextWeight)
        val floatNames = listOf(DataStoreKey.einkTextDarkness, DataStoreKey.einkParagraphGap, DataStoreKey.einkParagraphIndent, DataStoreKey.einkImageContrast)
        intNames.forEach { name ->
            assertEquals(intPreferencesKey(name), DataStoreKey.keys[name]?.key)
            assertEquals(intPreferencesKey(name), PreferencesKey.keys[name]?.key)
        }
        floatNames.forEach { name ->
            assertEquals(floatPreferencesKey(name), DataStoreKey.keys[name]?.key)
            assertEquals(floatPreferencesKey(name), PreferencesKey.keys[name]?.key)
        }
        assertEquals(booleanPreferencesKey(DataStoreKey.einkImageGrayscale), DataStoreKey.keys[DataStoreKey.einkImageGrayscale]?.key)
        assertEquals(booleanPreferencesKey(DataStoreKey.einkImageGrayscale), PreferencesKey.keys[DataStoreKey.einkImageGrayscale]?.key)
    }

    private fun ReaderFormatting.margins() = listOf(marginLeft, marginRight, marginTop, marginBottom)

    /** DataStore's real Preferences/edit API is used; this records its transaction boundary. */
    private class RecordingDataStore(
        initial: Preferences = emptyPreferences(),
        private val failCommit: Boolean = false,
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        val current get() = state.value
        var updateCalls = 0
        val snapshotsBeforeUpdate = mutableListOf<Preferences>()
        val committed = mutableListOf<Preferences>()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            updateCalls++
            snapshotsBeforeUpdate += current
            val result = transform(current)
            if (failCommit) throw IOException("Test commit failure")
            state.value = result
            committed += result
            return result
        }
    }
}
