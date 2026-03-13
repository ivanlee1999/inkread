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
    val values = (0..5).toList()
    val names = listOf(
        "System Default",
        "Noto Serif SC (思源宋体)",
        "Noto Sans SC (思源黑体)",
        "LXGW WenKai (霞鹜文楷)",
        "ZCOOL XiaoWei (站酷小薇体)",
        "Ma Shan Zheng (马善政楷体)",
    )
    const val default = 0

    val fontFamilyCss = listOf(
        "",
        "'NotoSerifSC'",
        "'NotoSansSC'",
        "'LXGWWenKai'",
        "'ZCOOLXiaoWei'",
        "'MaShanZheng'",
    )

    val fontCssNames = listOf<String?>(
        null, "NotoSerifSC", "NotoSansSC", "LXGWWenKai", "ZCOOLXiaoWei", "MaShanZheng",
    )

    val fontFileNames = listOf<String?>(
        null,
        "NotoSerifSC-Regular.ttf",
        "NotoSansSC-Regular.otf",
        "LXGWWenKai-Regular.ttf",
        "ZCOOLXiaoWei-Regular.ttf",
        "MaShanZheng-Regular.ttf",
    )

    val fontUrls = listOf<String?>(
        null,
        "https://fonts.gstatic.com/s/notoserifsc/v35/H4cyBXePl9DZ0Xe7gG9cyOj7uK2-n-D2rd4FY7SCqyWv.ttf",
        "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf",
        "https://github.com/lxgw/LxgwWenKai/releases/download/v1.501/LXGWWenKai-Regular.ttf",
        "https://fonts.gstatic.com/s/zcoolxiaowei/v15/i7dMIFFrTRywPpUVX9_RJyM1YFI.ttf",
        "https://fonts.gstatic.com/s/mashanzheng/v17/NaPecZTRCLxvwo41b4gvzkXaRMQ.ttf",
    )

    fun put(context: Context, scope: CoroutineScope, value: Int) {
        scope.launch {
            context.dataStore.put(einkChineseFont, value)
        }
    }

    fun fromPreferences(preferences: Preferences): Int =
        preferences[DataStoreKey.keys[einkChineseFont]?.key as Preferences.Key<Int>] ?: default
}
