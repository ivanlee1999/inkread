package me.ash.reader.infrastructure.preference

import android.content.Context
import android.os.Build
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.einkMode
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalEInkMode =
    compositionLocalOf<EInkModePreference> { EInkModePreference.default }

sealed class EInkModePreference(val value: Int) : Preference() {
    object Auto : EInkModePreference(0)
    object ON : EInkModePreference(1)
    object OFF : EInkModePreference(2)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(einkMode, value)
        }
    }

    fun isEInkMode(): Boolean = when (this) {
        Auto -> isEInkDevice()
        ON -> true
        OFF -> false
    }

    companion object {
        val default = Auto
        val values = listOf(Auto, ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[einkMode]?.key as Preferences.Key<Int>]) {
                0 -> Auto
                1 -> ON
                2 -> OFF
                else -> default
            }

        fun isEInkDevice(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val model = Build.MODEL.lowercase()
            return manufacturer.contains("onyx") ||
                    manufacturer.contains("dasung") ||
                    manufacturer.contains("bigme") ||
                    model.contains("palma") ||
                    model.contains("boox")
        }
    }
}
