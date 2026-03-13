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
    /** 0=System, 1=SourceSerif4, 2=Literata, 3=Merriweather, 4=Lora, 5=EBGaramond */
    val values = listOf(0, 1, 2, 3, 4, 5)
    val names = listOf("System Serif", "Source Serif 4", "Literata", "Merriweather", "Lora", "EB Garamond")
    const val default = 0

    /** CSS font-family string for each option. */
    val fontFamilyCss = listOf(
        "Georgia, serif",
        "'SourceSerif4', Georgia, serif",
        "'Literata', Georgia, serif",
        "'Merriweather', Georgia, serif",
        "'Lora', Georgia, serif",
        "'EBGaramond', Georgia, serif",
    )

    /** CSS font-family name used in @font-face (null = system, no download needed). */
    val fontCssNames = listOf<String?>(null, "SourceSerif4", "Literata", "Merriweather", "Lora", "EBGaramond")

    /** Local filename to store in filesDir/fonts/ (null = system font). */
    val fontFileNames = listOf<String?>(
        null,
        "SourceSerif4-Regular.ttf",
        "Literata-Regular.ttf",
        "Merriweather-Regular.ttf",
        "Lora-Regular.ttf",
        "EBGaramond-Regular.ttf",
    )

    /** Download URL for each font (null = system font, no download needed). */
    val fontUrls = listOf<String?>(
        null,
        "https://github.com/adobe-fonts/source-serif/raw/release/TTF/SourceSerif4-Regular.ttf",
        "https://github.com/googlefonts/literata/raw/main/fonts/ttf/Literata-Regular.ttf",
        "https://github.com/SorkinType/Merriweather/raw/master/fonts/ttf/Merriweather-Regular.ttf",
        "https://github.com/cyrealtype/Lora-Cyrillic/raw/master/fonts/ttf/Lora-Regular.ttf",
        "https://github.com/georgd/EB-Garamond/raw/master/EB%20Garamond%20Regular.ttf",
    )

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(einkEnglishFont, value)
        }
    }

    fun fromPreferences(preferences: Preferences): Int =
        preferences[DataStoreKey.keys[einkEnglishFont]?.key as Preferences.Key<Int>] ?: default
}
