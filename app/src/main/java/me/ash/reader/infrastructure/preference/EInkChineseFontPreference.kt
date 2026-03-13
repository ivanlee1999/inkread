package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.einkChineseFont
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalEInkChineseFont = compositionLocalOf { EInkChineseFontPreference.default }

object EInkChineseFontPreference {
    /** 0 = System default, 1 = Noto Serif SC (bundled) */
    val values = listOf(0, 1)
    val names = listOf("System Default", "Noto Serif SC")
    const val default = 0

    /**
     * Additional CSS font-family entry to insert for Chinese text.
     * Empty string means no override (system default).
     */
    val fontFamilyCss = listOf(
        "",                   // System: no override
        "'NotoSerifSC'",      // Noto Serif SC bundled
    )

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(einkChineseFont, value)
        }
    }

    fun fromPreferences(preferences: Preferences): Int =
        preferences[DataStoreKey.keys[einkChineseFont]?.key as Preferences.Key<Int>] ?: default
}
