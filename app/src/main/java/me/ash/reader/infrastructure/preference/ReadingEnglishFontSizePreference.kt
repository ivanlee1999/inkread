package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.readingEnglishFontSize
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalReadingEnglishFontSize = compositionLocalOf { ReadingEnglishFontSizePreference.default }

object ReadingEnglishFontSizePreference {

    const val default = 16

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(DataStoreKey.readingEnglishFontSize, value)
        }
    }

    fun fromPreferences(preferences: Preferences) =
        preferences[(DataStoreKey.keys[readingEnglishFontSize]?.key as? Preferences.Key<Int>) ?: return default] ?: default
}
