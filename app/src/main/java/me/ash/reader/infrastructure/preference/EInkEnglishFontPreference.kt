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
    val values = (0..9).toList()
    val names = listOf(
        "System Serif",
        "Source Serif 4",
        "Literata",
        "Merriweather",
        "Crimson Pro",
        "Crimson Text",
        "Libre Baskerville",
        "PT Serif",
        "IBM Plex Serif",
        "Gentium Book Plus",
    )
    const val default = 0

    val fontFamilyCss = listOf(
        "Georgia, serif",
        "'SourceSerif4', Georgia, serif",
        "'Literata', Georgia, serif",
        "'Merriweather', Georgia, serif",
        "'CrimsonPro', Georgia, serif",
        "'CrimsonText', Georgia, serif",
        "'LibreBaskerville', Georgia, serif",
        "'PTSerif', Georgia, serif",
        "'IBMPlexSerif', Georgia, serif",
        "'GentiumBookPlus', Georgia, serif",
    )

    val fontCssNames = listOf<String?>(
        null, "SourceSerif4", "Literata", "Merriweather",
        "CrimsonPro", "CrimsonText", "LibreBaskerville",
        "PTSerif", "IBMPlexSerif", "GentiumBookPlus",
    )

    val fontFileNames = listOf<String?>(
        null,
        "SourceSerif4-Regular.ttf",
        "Literata-Regular.ttf",
        "Merriweather-Regular.ttf",
        "CrimsonPro-Regular.ttf",
        "CrimsonText-Regular.ttf",
        "LibreBaskerville-Regular.ttf",
        "PTSerif-Regular.ttf",
        "IBMPlexSerif-Regular.ttf",
        "GentiumBookPlus-Regular.ttf",
    )

    val fontUrls = listOf<String?>(
        null,
        "https://fonts.gstatic.com/s/sourceserif4/v8/vEFy2_tTDB4M7-auWDN0ahZJW3IX2ih5nk3AucvUHf6OAVIts-AYlMKXAkfQ.ttf",
        "https://fonts.gstatic.com/s/literata/v39/or3PQ6P12-iJxAIgLa78DkrbXsDgk0oVDaDPYLanFLHpPf2TbJG_F_bcTWCWp8g.ttf",
        "https://fonts.gstatic.com/s/merriweather/v30/u-440qyriQwlOrhSvowK_l5-fCZM.ttf",
        "https://fonts.gstatic.com/s/crimsonpro/v28/q5uUsoa5M_tv7IihmnkabC5XiXCAlXGks1WZzm18OA.ttf",
        "https://fonts.gstatic.com/s/crimsontext/v19/wlp2gwHKFkZgtmSR3NB0oRJvaA.ttf",
        "https://fonts.gstatic.com/s/librebaskerville/v24/kmKUZrc3Hgbbcjq75U4uslyuy4kn0olVQ-LglH6T17uj8Q4SCQ.ttf",
        "https://fonts.gstatic.com/s/ptserif/v19/EJRVQgYoZZY2vCFuvDFR.ttf",
        "https://fonts.gstatic.com/s/ibmplexserif/v20/jizDREVNn1dOx-zrZ2X3pZvkThUY.ttf",
        "https://fonts.gstatic.com/s/gentiumbookplus/v1/vEFL2-RHBgUK5fbjKxRpbBtJPyRpofKf.ttf",
    )

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(einkEnglishFont, value)
        }
    }

    fun fromPreferences(preferences: Preferences): Int =
        preferences[DataStoreKey.keys[einkEnglishFont]?.key as Preferences.Key<Int>] ?: default
}
