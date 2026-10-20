package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.layout.ReadingLayoutPreset

val LocalReadingLayoutPreset =
    compositionLocalOf<ReadingLayoutPresetPreference> { ReadingLayoutPresetPreference.default }

sealed class ReadingLayoutPresetPreference(val preset: ReadingLayoutPreset) : Preference() {
    data object Auto : ReadingLayoutPresetPreference(ReadingLayoutPreset.Auto)
    data object Phone : ReadingLayoutPresetPreference(ReadingLayoutPreset.Phone)
    data object Tablet : ReadingLayoutPresetPreference(ReadingLayoutPreset.Tablet)

    val value: Int get() = preset.persistedValue

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch { context.dataStore.put(DataStoreKey.readingLayoutPreset, value) }
    }

    fun toDesc(context: Context): String = context.getString(
        when (this) {
            Auto -> R.string.layout_preset_auto
            Phone -> R.string.layout_preset_phone
            Tablet -> R.string.layout_preset_tablet
        }
    )

    fun description(context: Context): String = context.getString(
        when (this) {
            Auto -> R.string.layout_preset_auto_description
            Phone -> R.string.layout_preset_phone_description
            Tablet -> R.string.layout_preset_tablet_description
        }
    )

    companion object {
        val default = Auto
        val values = listOf(Auto, Phone, Tablet)
        private val key = intPreferencesKey(DataStoreKey.readingLayoutPreset)

        fun fromPreferences(preferences: Preferences): ReadingLayoutPresetPreference =
            when (ReadingLayoutPreset.fromValue(preferences[key])) {
                ReadingLayoutPreset.Auto -> Auto
                ReadingLayoutPreset.Phone -> Phone
                ReadingLayoutPreset.Tablet -> Tablet
            }
    }
}
