package me.ash.reader.infrastructure.preference

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Local selection convenience, independent of consent to export to Readwise. */
object AnnotationPreferences {
    private val autoHighlight = booleanPreferencesKey("annotationAutoHighlight")
    fun fromPreferences(preferences: Preferences): Boolean = preferences[autoHighlight] ?: false
    fun observeAutoHighlight(store: DataStore<Preferences>): Flow<Boolean> = store.data
        .map(::fromPreferences)
        .catch { emit(false) }
        .distinctUntilChanged()
    suspend fun setAutoHighlight(store: DataStore<Preferences>, enabled: Boolean) {
        store.edit { it[autoHighlight] = enabled }
    }
}
