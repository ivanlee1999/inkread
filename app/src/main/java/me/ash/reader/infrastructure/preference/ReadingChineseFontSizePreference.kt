package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.readingChineseFontSize
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalReadingChineseFontSize = compositionLocalOf { ReadingChineseFontSizePreference.default }

object ReadingChineseFontSizePreference {

    const val default = 20

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(DataStoreKey.readingChineseFontSize, value)
        }
    }

    fun fromPreferences(preferences: Preferences): Int {
        val key = DataStoreKey.keys[readingChineseFontSize]?.key as? Preferences.Key<Int> ?: return default
        return preferences[key] ?: default
    }
}
