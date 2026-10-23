package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.*
import org.junit.Test

class AnnotationPreferencesTest {
    @Test fun missingPreferenceIsOff() {
        assertFalse(AnnotationPreferences.fromPreferences(emptyPreferences()))
    }
    @Test fun onlyExplicitLocalSelectionPreferenceEnablesIt() {
        assertTrue(AnnotationPreferences.fromPreferences(preferencesOf(booleanPreferencesKey("annotationAutoHighlight") to true)))
        assertFalse(AnnotationPreferences.fromPreferences(preferencesOf(booleanPreferencesKey("annotationAutoHighlight") to false)))
        assertFalse(AnnotationPreferences.fromPreferences(preferencesOf(booleanPreferencesKey("readwiseAutoExport") to true)))
    }
}
