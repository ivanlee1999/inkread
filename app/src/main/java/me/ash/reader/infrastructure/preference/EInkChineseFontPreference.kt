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
    /** 0=System, 1=NotoSerifSC, 2=LXGWWenKai, 3=SourceHanSerifSC */
    val values = listOf(0, 1, 2, 3)
    val names = listOf("System Default", "Noto Serif SC", "LXGW WenKai", "Source Han Serif SC")
    const val default = 0

    /**
     * Additional CSS font-family entry to insert for Chinese text.
     * Empty string means no override (system default).
     */
    val fontFamilyCss = listOf(
        "",               // System: no override
        "'NotoSerifSC'",
        "'LXGWWenKai'",
        "'SourceHanSerifSC'",
    )

    /** CSS font-family name used in @font-face (null = system, no download needed). */
    val fontCssNames = listOf<String?>(null, "NotoSerifSC", "LXGWWenKai", "SourceHanSerifSC")

    /** Local filename to store in filesDir/fonts/ (null = system font). */
    val fontFileNames = listOf<String?>(
        null,
        "NotoSerifSC-Regular.otf",
        "LXGWWenKai-Regular.ttf",
        "SourceHanSerifSC-Regular.otf",
    )

    /** Download URL for each font (null = system font, no download needed). */
    val fontUrls = listOf<String?>(
        null,
        "https://github.com/notofonts/noto-cjk/raw/main/Serif/SubsetOTF/SC/NotoSerifSC-Regular.otf",
        "https://github.com/lxgw/LxgwWenKai/releases/download/v1.501/LXGWWenKai-Regular.ttf",
        "https://github.com/adobe-fonts/source-han-serif/raw/release/SubsetOTF/SC/SourceHanSerifSC-Regular.otf",
    )

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(einkChineseFont, value)
        }
    }

    fun fromPreferences(preferences: Preferences): Int =
        preferences[DataStoreKey.keys[einkChineseFont]?.key as Preferences.Key<Int>] ?: default
}
