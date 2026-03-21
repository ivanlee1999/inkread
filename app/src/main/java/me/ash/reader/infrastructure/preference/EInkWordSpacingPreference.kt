package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.einkWordSpacing
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalEInkWordSpacing = compositionLocalOf { EInkWordSpacingPreference.default }

object EInkWordSpacingPreference {
    const val default = 0f

    fun put(context: Context, scope: CoroutineScope, value: Float) {
        scope.launch {
            context.dataStore.put(DataStoreKey.einkWordSpacing, value)
        }
    }

    fun fromPreferences(preferences: Preferences): Float =
        preferences[DataStoreKey.keys[einkWordSpacing]?.key as Preferences.Key<Float>] ?: default
}
