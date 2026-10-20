package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.PreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingLayoutPresetPreferenceTest {
    private val key = intPreferencesKey(DataStoreKey.readingLayoutPreset)

    @Test fun missingPreferenceDefaultsToAutoWithoutMigratingOtherSettings() {
        val existingFontKey = intPreferencesKey(DataStoreKey.readingTextFontSize)
        val preferences = preferencesOf(existingFontKey to 24)
        assertEquals(ReadingLayoutPresetPreference.Auto, ReadingLayoutPresetPreference.fromPreferences(preferences))
        assertEquals(24, preferences[existingFontKey])
        assertEquals(null, preferences[key])
    }

    @Test fun everySavedPresetRestoresUsingItsStableValue() {
        ReadingLayoutPresetPreference.values.forEach { preset ->
            val preferences = preferencesOf(key to preset.value)
            assertEquals(preset, ReadingLayoutPresetPreference.fromPreferences(preferences))
        }
    }

    @Test fun unknownSavedValueFallsBackToAuto() {
        listOf(-1, 3, Int.MAX_VALUE).forEach { value ->
            assertEquals(
                ReadingLayoutPresetPreference.Auto,
                ReadingLayoutPresetPreference.fromPreferences(preferencesOf(key to value)),
            )
        }
    }

    @Test fun bothPreferenceRegistriesUseTheSameIntegerKey() {
        assertEquals(key, DataStoreKey.keys[DataStoreKey.readingLayoutPreset]?.key)
        assertEquals(key, PreferencesKey.keys[PreferencesKey.readingLayoutPreset]?.key)
    }
}
