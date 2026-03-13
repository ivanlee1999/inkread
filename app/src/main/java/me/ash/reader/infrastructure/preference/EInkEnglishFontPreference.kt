package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.einkEnglishFont
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalEInkEnglishFont = compositionLocalOf { EInkEnglishFontPreference.default }

object EInkEnglishFontPreference {
    /** 0 = System Serif (Georgia), 1 = Source Serif 4 (bundled) */
    val values = listOf(0, 1)
    val names = listOf("System Serif", "Source Serif 4")
    const val default = 0

    /** CSS font-family string for each option. */
    val fontFamilyCss = listOf(
        "Georgia, serif",
        "'SourceSerif4', Georgia, serif",
    )

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(einkEnglishFont, value)
        }
    }

    fun fromPreferences(preferences: Preferences): Int =
        preferences[DataStoreKey.keys[einkEnglishFont]?.key as Preferences.Key<Int>] ?: default
}
