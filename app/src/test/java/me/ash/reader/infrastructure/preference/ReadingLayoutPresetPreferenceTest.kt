package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.PreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test fun companionNeverCapturesNullWhenAnySingletonIsInitializedFirst() {
        val baseName = ReadingLayoutPresetPreference::class.java.name
        listOf("Auto", "Phone", "Tablet").forEach { first ->
            // Each order needs a fresh JVM class initialization, independent of test order.
            val loader = IsolatedPreferenceClassLoader(baseName)
            Class.forName("$baseName\$$first", true, loader)
            val preferenceClass = Class.forName(baseName, true, loader)
            val companion = preferenceClass.getField("Companion").get(null)
            val default = companion.javaClass.getMethod("getDefault").invoke(companion)
            assertNotNull("Default must exist after $first initializes first", default)
            assertEquals(0, preferenceClass.getMethod("getValue").invoke(default))

            val values = companion.javaClass.getMethod("getValues").invoke(companion) as List<*>
            assertEquals(3, values.size)
            values.forEach { assertNotNull("Values must not cache null after $first initializes first", it) }
            assertEquals(listOf(0, 1, 2), values.map { preferenceClass.getMethod("getValue").invoke(it) })
        }
    }

    /** Isolate only this sealed preference family; Android and DataStore remain on the test classpath. */
    private class IsolatedPreferenceClassLoader(private val baseName: String) :
        ClassLoader(ReadingLayoutPresetPreference::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            synchronized(getClassLoadingLock(name)) {
                val loaded = findLoadedClass(name) ?: if (name == baseName || name.startsWith("$baseName\$")) {
                    val resource = name.replace('.', '/') + ".class"
                    val bytes = requireNotNull(parent.getResourceAsStream(resource)).use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                } else {
                    super.loadClass(name, false)
                }
                if (resolve) resolveClass(loaded)
                loaded
            }
    }
}
