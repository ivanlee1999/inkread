package me.ash.reader.ui.page.settings.color

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.infrastructure.font.FontDownloader
import me.ash.reader.infrastructure.preference.BasicFontsPreference
import me.ash.reader.infrastructure.preference.CustomPrimaryColorPreference
import me.ash.reader.infrastructure.preference.EInkChineseFontPreference
import me.ash.reader.infrastructure.preference.EInkEnglishFontPreference
import me.ash.reader.infrastructure.preference.EInkFontSizePreference
import me.ash.reader.infrastructure.preference.EInkModePreference
import me.ash.reader.infrastructure.preference.LocalBasicFonts
import me.ash.reader.infrastructure.preference.LocalCustomPrimaryColor
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.infrastructure.preference.LocalEInkChineseFont
import me.ash.reader.infrastructure.preference.LocalEInkEnglishFont
import me.ash.reader.infrastructure.preference.LocalEInkFontSize
import me.ash.reader.infrastructure.preference.LocalEInkMode
import me.ash.reader.infrastructure.preference.LocalThemeIndex
import me.ash.reader.infrastructure.preference.ThemeIndexPreference
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.ui.component.base.BlockRadioButton
import me.ash.reader.ui.component.base.BlockRadioGroupButtonItem
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.DynamicSVGImage
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.component.base.RadioDialog
import me.ash.reader.ui.component.base.RadioDialogOption
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.ExternalFonts
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.svg.PALETTE
import me.ash.reader.ui.svg.SVGString
import me.ash.reader.ui.theme.palette.TonalPalettes
import me.ash.reader.ui.theme.palette.TonalPalettes.Companion.toTonalPalettes
import me.ash.reader.ui.theme.palette.checkColorHex
import me.ash.reader.ui.theme.palette.dynamic.extractTonalPalettesFromUserWallpaper
import me.ash.reader.ui.theme.palette.onDark
import me.ash.reader.ui.theme.palette.onLight
import me.ash.reader.ui.theme.palette.safeHexToColor

@Composable
fun ColorAndStylePage(
    onBack: () -> Unit,
    navigateToDarkTheme: () -> Unit,
    navigateToFeedsPageStyle: () -> Unit,
    navigateToFlowPageStyle: () -> Unit,
    navigateToReadingPageStyle: () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = LocalDarkTheme.current
    val darkThemeNot = !darkTheme
    val themeIndex = LocalThemeIndex.current
    val customPrimaryColor = LocalCustomPrimaryColor.current
    val fonts = LocalBasicFonts.current
    val einkMode = LocalEInkMode.current
    val einkEnglishFont = LocalEInkEnglishFont.current
    val einkChineseFont = LocalEInkChineseFont.current
    val einkFontSize = LocalEInkFontSize.current
    val scope = rememberCoroutineScope()

    val fontDownloader = remember { FontDownloader(context) }
    // Track which font files are already downloaded
    val downloadedFonts = remember {
        mutableStateMapOf<String, Boolean>().also { map ->
            EInkEnglishFontPreference.fontFileNames.filterNotNull().forEach { name ->
                map[name] = fontDownloader.getFontFile(name) != null
            }
            EInkChineseFontPreference.fontFileNames.filterNotNull().forEach { name ->
                map[name] = fontDownloader.getFontFile(name) != null
            }
        }
    }
    // Font currently being downloaded (file name), or null
    var downloadingFont by remember { mutableStateOf<String?>(null) }

    val wallpaperTonalPalettes = extractTonalPalettesFromUserWallpaper()
    var radioButtonSelected by remember { mutableStateOf(if (themeIndex > 4) 0 else 1) }
    var fontsDialogVisible by remember { mutableStateOf(false) }
    var englishFontDialogVisible by remember { mutableStateOf(false) }
    var chineseFontDialogVisible by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            ExternalFonts(context, it, ExternalFonts.FontType.BasicFont).copyToInternalStorage()
            BasicFontsPreference.External.put(context, scope)
        } ?: context.showToast("Cannot get activity result with launcher")
    }

    fun downloadAndSelect(
        fontIdx: Int,
        fileName: String,
        url: String,
        putPref: (Int) -> Unit,
    ) {
        if (downloadedFonts[fileName] == true) {
            putPref(fontIdx)
            return
        }
        downloadingFont = fileName
        scope.launch {
            try {
                fontDownloader.downloadFont(fileName, url)
                downloadedFonts[fileName] = true
                putPref(fontIdx)
            } catch (e: Exception) {
                context.showToast("Download failed: ${e.message}")
            } finally {
                downloadingFont = null
            }
        }
    }

    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack
            )
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.color_and_style), desc = "")
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .aspectRatio(1.38f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLow
                            )
                            .clickable { },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DynamicSVGImage(
                            modifier = Modifier.padding(60.dp),
                            svgImageString = SVGString.PALETTE,
                            contentDescription = stringResource(R.string.color_and_style),
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    BlockRadioButton(
                        selected = radioButtonSelected,
                        onSelected = { radioButtonSelected = it },
                        itemRadioGroups = listOf(
                            BlockRadioGroupButtonItem(
                                text = stringResource(R.string.wallpaper_colors),
                                onClick = {},
                            ) {
                                Palettes(
                                    context = context,
                                    palettes = wallpaperTonalPalettes.run {
                                        if (this.size > 5) {
                                            this.subList(5, wallpaperTonalPalettes.size)
                                        } else {
                                            emptyList()
                                        }
                                    },
                                    themeIndex = themeIndex,
                                    themeIndexPrefix = 5,
                                    customPrimaryColor = customPrimaryColor,
                                )
                            },
                            BlockRadioGroupButtonItem(
                                text = stringResource(R.string.basic_colors),
                                onClick = {},
                            ) {
                                Palettes(
                                    context = context,
                                    themeIndex = themeIndex,
                                    palettes = wallpaperTonalPalettes.subList(0, 5),
                                    customPrimaryColor = customPrimaryColor,
                                )
                            },
                        ),
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.appearance),
                    )
                    SettingItem(
                        title = stringResource(R.string.dark_theme),
                        desc = darkTheme.toDesc(context),
                        separatedActions = true,
                        onClick = navigateToDarkTheme,
                    ) {
                        RYSwitch(
                            activated = darkTheme.isDarkTheme()
                        ) {
                            darkThemeNot.put(context, scope)
                        }
                    }
                    SettingItem(
                        title = "E-Ink Mode",
                        desc = when (einkMode) {
                            EInkModePreference.Auto -> "Auto (detected: ${if (einkMode.isEInkMode()) "on" else "off"})"
                            EInkModePreference.ON -> "Always on"
                            EInkModePreference.OFF -> "Off"
                        },
                        onClick = {
                            when (einkMode) {
                                EInkModePreference.ON -> EInkModePreference.OFF.put(context, scope)
                                else -> EInkModePreference.ON.put(context, scope)
                            }
                        },
                    ) {
                        RYSwitch(activated = einkMode.isEInkMode()) {
                            when (einkMode) {
                                EInkModePreference.ON -> EInkModePreference.OFF.put(context, scope)
                                else -> EInkModePreference.ON.put(context, scope)
                            }
                        }
                    }
                    // English font — shows download status, opens dialog
                    val englishFileName = EInkEnglishFontPreference.fontFileNames.getOrNull(einkEnglishFont)
                    val englishDownloading = englishFileName != null && downloadingFont == englishFileName
                    val englishDesc = when {
                        englishDownloading -> "Downloading ${EInkEnglishFontPreference.names[einkEnglishFont]}…"
                        englishFileName == null -> EInkEnglishFontPreference.names[einkEnglishFont]
                        downloadedFonts[englishFileName] == true -> "${EInkEnglishFontPreference.names[einkEnglishFont]} • Downloaded"
                        else -> "${EInkEnglishFontPreference.names[einkEnglishFont]} • Tap to download"
                    }
                    SettingItem(
                        title = "E-Ink English Font",
                        desc = englishDesc,
                        onClick = { englishFontDialogVisible = true },
                    ) {
                        if (englishDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    // Chinese font — shows download status, opens dialog
                    val chineseFileName = EInkChineseFontPreference.fontFileNames.getOrNull(einkChineseFont)
                    val chineseDownloading = chineseFileName != null && downloadingFont == chineseFileName
                    val chineseDesc = when {
                        chineseDownloading -> "Downloading ${EInkChineseFontPreference.names[einkChineseFont]}…"
                        chineseFileName == null -> EInkChineseFontPreference.names[einkChineseFont]
                        downloadedFonts[chineseFileName] == true -> "${EInkChineseFontPreference.names[einkChineseFont]} • Downloaded"
                        else -> "${EInkChineseFontPreference.names[einkChineseFont]} • Tap to download"
                    }
                    SettingItem(
                        title = "E-Ink Chinese Font",
                        desc = chineseDesc,
                        onClick = { chineseFontDialogVisible = true },
                    ) {
                        if (chineseDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    SettingItem(
                        title = "E-Ink Font Size",
                        desc = "${einkFontSize}sp",
                        onClick = {
                            val nextIdx = (EInkFontSizePreference.values.indexOf(einkFontSize) + 1) % EInkFontSizePreference.values.size
                            EInkFontSizePreference.put(context, scope, EInkFontSizePreference.values[nextIdx])
                        },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.basic_fonts),
                        desc = fonts.toDesc(context),
                        onClick = { fontsDialogVisible = true },
                    ) {}
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.style)
                    )
                    SettingItem(
                        title = stringResource(R.string.feeds_page),
                        onClick = navigateToFeedsPageStyle,
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.flow_page),
                        onClick = navigateToFlowPageStyle,
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.reading_page),
                        onClick = navigateToReadingPageStyle,
                    ) {}
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    )

    // English font picker dialog
    RadioDialog(
        visible = englishFontDialogVisible,
        title = "E-Ink English Font",
        options = EInkEnglishFontPreference.values.map { idx ->
            val fileName = EInkEnglishFontPreference.fontFileNames.getOrNull(idx)
            val isDownloaded = fileName == null || downloadedFonts[fileName] == true
            val statusSuffix = when {
                fileName == null -> ""
                isDownloaded -> " • Downloaded"
                else -> " • Tap to download"
            }
            RadioDialogOption(
                text = EInkEnglishFontPreference.names[idx] + statusSuffix,
                selected = idx == einkEnglishFont,
            ) {
                if (fileName == null || isDownloaded) {
                    EInkEnglishFontPreference.put(context, scope, idx)
                } else {
                    val url = EInkEnglishFontPreference.fontUrls.getOrNull(idx) ?: return@RadioDialogOption
                    downloadAndSelect(idx, fileName, url) { i ->
                        EInkEnglishFontPreference.put(context, scope, i)
                    }
                }
            }
        },
        onDismissRequest = { englishFontDialogVisible = false },
    )

    // Chinese font picker dialog
    RadioDialog(
        visible = chineseFontDialogVisible,
        title = "E-Ink Chinese Font",
        options = EInkChineseFontPreference.values.map { idx ->
            val fileName = EInkChineseFontPreference.fontFileNames.getOrNull(idx)
            val isDownloaded = fileName == null || downloadedFonts[fileName] == true
            val statusSuffix = when {
                fileName == null -> ""
                isDownloaded -> " • Downloaded"
                else -> " • Tap to download"
            }
            RadioDialogOption(
                text = EInkChineseFontPreference.names[idx] + statusSuffix,
                selected = idx == einkChineseFont,
            ) {
                if (fileName == null || isDownloaded) {
                    EInkChineseFontPreference.put(context, scope, idx)
                } else {
                    val url = EInkChineseFontPreference.fontUrls.getOrNull(idx) ?: return@RadioDialogOption
                    downloadAndSelect(idx, fileName, url) { i ->
                        EInkChineseFontPreference.put(context, scope, i)
                    }
                }
            }
        },
        onDismissRequest = { chineseFontDialogVisible = false },
    )

    RadioDialog(
        visible = fontsDialogVisible,
        title = stringResource(R.string.basic_fonts),
        options = BasicFontsPreference.values.map {
            RadioDialogOption(
                text = it.toDesc(context),
                style = TextStyle(fontFamily = it.asFontFamily(context)),
                selected = it == fonts,
            ) {
                if (it.value == BasicFontsPreference.External.value) {
                    launcher.launch(arrayOf(MimeType.FONT))
                } else {
                    it.put(context, scope)
                }
            }
        }
    ) {
        fontsDialogVisible = false
    }
}

@Composable
fun Palettes(
    context: Context,
    palettes: List<TonalPalettes>,
    themeIndex: Int = 0,
    themeIndexPrefix: Int = 0,
    customPrimaryColor: String = "",
) {
    val scope = rememberCoroutineScope()
    val tonalPalettes = customPrimaryColor.safeHexToColor().toTonalPalettes()
    var addDialogVisible by remember { mutableStateOf(false) }
    var customColorValue by remember { mutableStateOf(customPrimaryColor) }

    if (palettes.isEmpty()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    MaterialTheme.colorScheme.inverseOnSurface
                            onLight MaterialTheme.colorScheme.surfaceContainer,
                )
                .clickable {},
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                    stringResource(R.string.no_palettes)
                else stringResource(R.string.only_android_8_1_plus),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inverseSurface,
            )
        }
    } else {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            palettes.forEachIndexed { index, palette ->
                val isCustom = index == palettes.lastIndex && themeIndexPrefix == 0
                val i = themeIndex - themeIndexPrefix
                SelectableMiniPalette(
                    selected = if (i >= palettes.size) i == 0 else i == index,
                    isCustom = isCustom,
                    onClick = {
                        if (isCustom) {
                            customColorValue = customPrimaryColor
                            addDialogVisible = true
                        } else {
                            ThemeIndexPreference.put(context, scope, themeIndexPrefix + index)
                        }
                    },
                    palette = if (isCustom) tonalPalettes else palette
                )
            }
        }
    }

    TextFieldDialog(
        visible = addDialogVisible,
        title = stringResource(R.string.primary_color),
        icon = Icons.Outlined.Palette,
        value = customColorValue,
        placeholder = stringResource(R.string.primary_color_hint),
        onValueChange = {
            customColorValue = it
        },
        onDismissRequest = {
            addDialogVisible = false
        },
        onConfirm = {
            it.checkColorHex()?.let {
                CustomPrimaryColorPreference.put(context, scope, it)
                ThemeIndexPreference.put(context, scope, 4)
                addDialogVisible = false
            }
        }
    )
}

@Composable
fun SelectableMiniPalette(
    modifier: Modifier = Modifier,
    selected: Boolean,
    isCustom: Boolean = false,
    onClick: () -> Unit,
    palette: TonalPalettes,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (isCustom) {
            MaterialTheme.colorScheme.primaryContainer
                .copy(0.5f) onDark MaterialTheme.colorScheme.onPrimaryContainer.copy(0.3f)
        } else {
            MaterialTheme.colorScheme
                .inverseOnSurface onLight MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Surface(
            modifier = Modifier
                .clickable { onClick() }
                .padding(16.dp)
                .size(48.dp),
            shape = CircleShape,
            color = palette primary 90,
        ) {
            Box {
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .offset((-24).dp, 24.dp),
                    color = palette tertiary 90,
                ) {}
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .offset(24.dp, 24.dp),
                    color = palette secondary 60,
                ) {}
                AnimatedVisibility(
                    visible = selected,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    enter = fadeIn() + expandIn(expandFrom = Alignment.Center),
                    exit = shrinkOut(shrinkTowards = Alignment.Center) + fadeOut()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Checked",
                        modifier = Modifier
                            .padding(8.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
    }
}
