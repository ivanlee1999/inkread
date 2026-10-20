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
import me.ash.reader.ui.layout.ReadingColumns

val LocalReadingColumns =
    compositionLocalOf<ReadingColumnsPreference> { ReadingColumnsPreference.default }

/** Shared by the article, subscription, and folder publication grids. */
sealed class ReadingColumnsPreference(val columns: ReadingColumns) : Preference() {
    data object Auto : ReadingColumnsPreference(ReadingColumns.Auto)
    data object One : ReadingColumnsPreference(ReadingColumns.One)
    data object Two : ReadingColumnsPreference(ReadingColumns.Two)

    val value: Int get() = columns.persistedValue

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch { context.dataStore.put(DataStoreKey.readingColumns, value) }
    }

    fun toDesc(context: Context): String = context.getString(
        when (this) {
            Auto -> R.string.reading_columns_auto
            One -> R.string.reading_columns_one
            Two -> R.string.reading_columns_two
        }
    )

    fun description(context: Context): String = context.getString(
        when (this) {
            Auto -> R.string.reading_columns_auto_description
            One -> R.string.reading_columns_one_description
            Two -> R.string.reading_columns_two_description
        }
    )

    companion object {
        // Getters avoid capturing a null INSTANCE if a singleton initializes before this class.
        val default: ReadingColumnsPreference get() = Auto
        val values: List<ReadingColumnsPreference> get() = listOf(Auto, One, Two)
        private val key = intPreferencesKey(DataStoreKey.readingColumns)

        fun fromPreferences(preferences: Preferences): ReadingColumnsPreference =
            when (ReadingColumns.fromValue(preferences[key])) {
                ReadingColumns.Auto -> Auto
                ReadingColumns.One -> One
                ReadingColumns.Two -> Two
            }
    }
}
