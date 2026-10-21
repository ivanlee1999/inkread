package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.PreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReadingColumnsPreferenceTest {
    private val key = intPreferencesKey(DataStoreKey.readingColumns)

    @Test fun missingPreferenceDefaultsToAutoWithoutMigratingOtherSettings() {
        val existingFontKey = intPreferencesKey(DataStoreKey.readingTextFontSize)
        val existingPresetKey = intPreferencesKey(DataStoreKey.readingLayoutPreset)
        val preferences = preferencesOf(existingFontKey to 24, existingPresetKey to 1)
        assertEquals(ReadingColumnsPreference.Auto, ReadingColumnsPreference.fromPreferences(preferences))
        assertEquals(24, preferences[existingFontKey])
        assertEquals(1, preferences[existingPresetKey])
        assertEquals(ReadingLayoutPresetPreference.Phone, ReadingLayoutPresetPreference.fromPreferences(preferences))
        assertEquals(null, preferences[key])
    }

    @Test fun everySavedChoiceRestoresUsingItsStableValue() {
        ReadingColumnsPreference.values.forEach { choice ->
            val preferences = preferencesOf(key to choice.value)
            assertEquals(choice, ReadingColumnsPreference.fromPreferences(preferences))
        }
    }

    @Test fun unknownSavedValueFallsBackToAuto() {
        listOf(-1, 3, Int.MAX_VALUE).forEach { value ->
            assertEquals(
                ReadingColumnsPreference.Auto,
                ReadingColumnsPreference.fromPreferences(preferencesOf(key to value)),
            )
        }
    }

    @Test fun bothPreferenceRegistriesUseTheSameIntegerKey() {
        assertEquals(key, DataStoreKey.keys[DataStoreKey.readingColumns]?.key)
        assertEquals(key, PreferencesKey.keys[PreferencesKey.readingColumns]?.key)
    }

    @Test fun companionNeverCapturesNullWhenAnySingletonIsInitializedFirst() {
        val baseName = ReadingColumnsPreference::class.java.name
        listOf("Auto", "One", "Two").forEach { first ->
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
        ClassLoader(ReadingColumnsPreference::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            synchronized(this) {
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
