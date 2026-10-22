package me.ash.reader.ui.page.home.reading

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.infrastructure.font.FontDownloader
import me.ash.reader.infrastructure.preference.EInkChineseFontPreference
import me.ash.reader.infrastructure.preference.EInkEnglishFontPreference
import me.ash.reader.infrastructure.preference.ReaderFormatting
import me.ash.reader.infrastructure.preference.ReaderTextAlignment
import java.util.Locale
import kotlin.math.round

/**
 * Edits only [draft]. The reader owns its lifetime, the visible article, and Apply/Cancel/Reset.
 * The host must allocate a bounded bottom/side region and handle Back/outside dismissal as Cancel.
 * No preferences are written here. A font download happens only after its explicit Download action;
 * the cached file is retained independently of whether the formatting draft is subsequently applied.
 */
@Composable
fun ReaderFormattingPanel(
    draft: ReaderFormatting,
    onDraftChange: (ReaderFormatting) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onFontAvailabilityChanged: () -> Unit = {},
    isSaving: Boolean = false,
    errorMessage: String? = null,
) {
    var section by remember { mutableStateOf(FormattingSection.Text) }
    val safeDraft = draft.clamped()
    val panelTitle = stringResource(R.string.reader_formatting_title)
    val fonts = rememberFontDownloads(onFontAvailabilityChanged)
    CompositionLocalProvider(LocalFormattingEnabled provides !isSaving, LocalOverscrollFactory provides null) {
        Surface(
            modifier = modifier.fillMaxWidth().testTag("reader_formatting_panel")
                .semantics { paneTitle = panelTitle },
            color = Color.White,
            contentColor = Color.Black,
            border = BorderStroke(1.dp, Color.Black),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(if (isSaving) R.string.reader_formatting_saving else R.string.reader_formatting_live_draft),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormattingSection.entries.forEach { item ->
                        FormattingButton(
                            label = stringResource(item.title),
                            modifier = Modifier.weight(1f).testTag("reader_formatting_section_${item.tag}"),
                            isSelected = section == item,
                            onClick = { section = item },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (section) {
                        FormattingSection.Text -> TextControls(safeDraft, onDraftChange)
                        FormattingSection.Layout -> LayoutControls(safeDraft, onDraftChange)
                        FormattingSection.Fonts -> FontControls(safeDraft, onDraftChange, fonts)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
                if (errorMessage != null) {
                    Text(errorMessage, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp).testTag("reader_formatting_error")
                            .semantics { liveRegion = LiveRegionMode.Polite })
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FormattingButton(
                        label = stringResource(R.string.reader_formatting_reset),
                        modifier = Modifier.weight(1f).testTag("reader_formatting_reset"),
                        onClick = { onReset() },
                    )
                    FormattingButton(
                        label = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f).testTag("reader_formatting_cancel"),
                        onClick = { onCancel() },
                    )
                    FormattingButton(
                        label = stringResource(R.string.apply),
                        modifier = Modifier.weight(1f).testTag("reader_formatting_apply"),
                        emphasized = true,
                        onClick = { onApply() },
                    )
                }
            }
        }
    }
}

private val LocalFormattingEnabled = staticCompositionLocalOf { true }
private object NoFormattingFling : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = initialVelocity
}

private enum class FormattingSection(@StringRes val title: Int, val tag: String) {
    Text(R.string.reader_formatting_text, "text"),
    Layout(R.string.reader_formatting_layout, "layout"),
    Fonts(R.string.reader_formatting_fonts, "fonts"),
}

@Composable
private fun TextControls(draft: ReaderFormatting, onChange: (ReaderFormatting) -> Unit) {
    LazyColumn(
        flingBehavior = NoFormattingFling,
        modifier = Modifier.fillMaxSize().testTag("reader_formatting_text_controls"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            NumberControl("font_size", stringResource(R.string.font_size), draft.fontSize.toFloat(),
                ReaderFormatting.FontSizeRange.asFloatRange(), 1f, 0, "px") {
                onChange(draft.copy(fontSize = it.toInt()).clamped())
            }
        }
        item {
            NumberControl("line_height", stringResource(R.string.reader_formatting_line_height), draft.lineHeight,
                ReaderFormatting.LineHeightRange, .05f, 2, "×") {
                onChange(draft.copy(lineHeight = it).clamped())
            }
        }
        item {
            NumberControl("text_darkness", stringResource(R.string.reader_formatting_darkness), draft.textDarkness * 100f,
                ReaderFormatting.TextDarknessRange.start * 100f..ReaderFormatting.TextDarknessRange.endInclusive * 100f,
                5f, 0, "%", stringResource(R.string.reader_formatting_darkness_hint)) {
                onChange(draft.copy(textDarkness = it / 100f).clamped())
            }
        }
        item {
            NumberControl("text_weight", stringResource(R.string.reader_formatting_weight), draft.textWeight.toFloat(),
                ReaderFormatting.TextWeightRange.asFloatRange(), 100f, 0, "",
                stringResource(R.string.reader_formatting_weight_hint)) {
                onChange(draft.copy(textWeight = it.toInt()).clamped())
            }
        }
        item {
            NumberControl("letter_spacing", stringResource(R.string.reader_formatting_letter_spacing), draft.letterSpacing,
                ReaderFormatting.LetterSpacingRange, .1f, 1, "px") {
                onChange(draft.copy(letterSpacing = it).clamped())
            }
        }
        item {
            NumberControl("word_spacing", stringResource(R.string.reader_formatting_word_spacing), draft.wordSpacing,
                ReaderFormatting.WordSpacingRange, .05f, 2, "em") {
                onChange(draft.copy(wordSpacing = it).clamped())
            }
        }
        item {
            Text(stringResource(R.string.reader_formatting_images), fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            NumberControl("image_contrast", stringResource(R.string.reader_formatting_image_contrast), draft.imageContrast * 100f,
                ReaderFormatting.ImageContrastRange.start * 100f..ReaderFormatting.ImageContrastRange.endInclusive * 100f,
                10f, 0, "%", stringResource(R.string.reader_formatting_image_contrast_hint)) {
                onChange(draft.copy(imageContrast = it / 100f).clamped())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FormattingButton(stringResource(R.string.reader_formatting_image_color),
                    modifier = Modifier.weight(1f).testTag("reader_formatting_image_color"),
                    isSelected = !draft.imageGrayscale,
                    onClick = { onChange(draft.copy(imageGrayscale = false)) })
                FormattingButton(stringResource(R.string.reader_formatting_image_grayscale),
                    modifier = Modifier.weight(1f).testTag("reader_formatting_image_grayscale"),
                    isSelected = draft.imageGrayscale,
                    onClick = { onChange(draft.copy(imageGrayscale = true)) })
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LayoutControls(draft: ReaderFormatting, onChange: (ReaderFormatting) -> Unit) {
    LazyColumn(
        flingBehavior = NoFormattingFling,
        modifier = Modifier.fillMaxSize().testTag("reader_formatting_layout_controls"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            NumberControl("margin_left", stringResource(R.string.reader_formatting_margin_left), draft.marginLeft.toFloat(),
                ReaderFormatting.MarginRange.asFloatRange(), 2f, 0, "px") {
                onChange(draft.copy(marginLeft = it.toInt()).clamped())
            }
        }
        item {
            NumberControl("margin_right", stringResource(R.string.reader_formatting_margin_right), draft.marginRight.toFloat(),
                ReaderFormatting.MarginRange.asFloatRange(), 2f, 0, "px") {
                onChange(draft.copy(marginRight = it.toInt()).clamped())
            }
        }
        item {
            NumberControl("margin_top", stringResource(R.string.reader_formatting_margin_top), draft.marginTop.toFloat(),
                ReaderFormatting.MarginRange.asFloatRange(), 2f, 0, "px") {
                onChange(draft.copy(marginTop = it.toInt()).clamped())
            }
        }
        item {
            NumberControl("margin_bottom", stringResource(R.string.reader_formatting_margin_bottom), draft.marginBottom.toFloat(),
                ReaderFormatting.MarginRange.asFloatRange(), 2f, 0, "px") {
                onChange(draft.copy(marginBottom = it.toInt()).clamped())
            }
        }
        item {
            Text(stringResource(R.string.reader_formatting_alignment), fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ReaderTextAlignment.Start, ReaderTextAlignment.End, ReaderTextAlignment.Center, ReaderTextAlignment.Justify)
                    .chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { alignment ->
                                val title = when (alignment) {
                                    ReaderTextAlignment.Start -> R.string.reader_formatting_align_start
                                    ReaderTextAlignment.End -> R.string.reader_formatting_align_end
                                    ReaderTextAlignment.Center -> R.string.reader_formatting_align_center
                                    ReaderTextAlignment.Justify -> R.string.reader_formatting_align_justify
                                }
                                FormattingButton(
                                    label = stringResource(title),
                                    modifier = Modifier.weight(1f).testTag("reader_formatting_alignment_${alignment.name.lowercase(Locale.ROOT)}"),
                                    isSelected = draft.alignment == alignment,
                                    onClick = { onChange(draft.copy(alignment = alignment)) },
                                )
                            }
                        }
                    }
            }
        }
        item {
            NumberControl("paragraph_gap", stringResource(R.string.reader_formatting_paragraph_gap), draft.paragraphGap,
                ReaderFormatting.ParagraphGapRange, .1f, 1, "em") {
                onChange(draft.copy(paragraphGap = it).clamped())
            }
        }
        item {
            NumberControl("paragraph_indent", stringResource(R.string.reader_formatting_paragraph_indent), draft.paragraphIndent,
                ReaderFormatting.ParagraphIndentRange, .1f, 1, "em") {
                onChange(draft.copy(paragraphIndent = it).clamped())
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Readable bounded values and large steppers preserve the live article without opening a keyboard. */
@Composable
private fun NumberControl(
    id: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    decimals: Int,
    unit: String,
    hint: String? = null,
    onChange: (Float) -> Unit,
) {
    val enabled = LocalFormattingEnabled.current
    val valueDescription = stringResource(R.string.reader_formatting_value_description, label, unit)
    val decreaseDescription = stringResource(R.string.reader_formatting_decrease, label)
    val increaseDescription = stringResource(R.string.reader_formatting_increase, label)
    val rangeDescription = stringResource(R.string.reader_formatting_range,
        formatNumber(range.start, decimals), formatNumber(range.endInclusive, decimals), unit)
    val visibleLabel = if (unit.isEmpty()) label else stringResource(R.string.reader_formatting_label_unit, label, unit)
    val controls: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FormattingButton(
                label = "−", description = decreaseDescription,
                modifier = Modifier.width(48.dp).testTag("reader_formatting_${id}_decrease"),
                enabled = value > range.start,
                onClick = { onChange(rounded(value - step).coerceIn(range)) },
            )
            Box(
                modifier = Modifier.width(84.dp).heightIn(min = 48.dp)
                    .testTag("reader_formatting_${id}_value")
                    .semantics(mergeDescendants = true) {
                        contentDescription = valueDescription
                        stateDescription = rangeDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(value, range,
                            (round((range.endInclusive - range.start) / step).toInt() - 1).coerceAtLeast(0))
                        if (enabled) {
                            setProgress { target ->
                                if (target.isFinite()) {
                                    val snapped = range.start + round((target - range.start) / step) * step
                                    onChange(rounded(snapped).coerceIn(range))
                                    true
                                } else false
                            }
                        } else disabled()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(formatNumber(value, decimals), style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black, textAlign = TextAlign.Center)
            }
            FormattingButton(
                label = "+", description = increaseDescription,
                modifier = Modifier.width(48.dp).testTag("reader_formatting_${id}_increase"),
                enabled = value < range.endInclusive,
                onClick = { onChange(rounded(value + step).coerceIn(range)) },
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.2f
        Column {
            if (stacked) {
                Text(visibleLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) { controls() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(visibleLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    controls()
                }
            }
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun IntRange.asFloatRange() = first.toFloat()..last.toFloat()
private fun rounded(value: Float) = round(value * 100f) / 100f
private fun formatNumber(value: Float, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)
private data class ReaderFontDownloads(
    val available: Map<String, Boolean>,
    val downloading: String?,
    val errorFont: String?,
    val download: (String, String) -> Unit,
)

/** Keep explicit downloads alive when switching sections, without re-selecting a stale draft. */
@Composable
private fun rememberFontDownloads(onFontAvailabilityChanged: () -> Unit): ReaderFontDownloads {
    val context = LocalContext.current
    val downloader = remember(context) { FontDownloader(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val availabilityChanged by rememberUpdatedState(onFontAvailabilityChanged)
    val downloaded = remember(downloader) {
        mutableStateMapOf<String, Boolean>().also { state ->
            (EInkEnglishFontPreference.fontFileNames + EInkChineseFontPreference.fontFileNames)
                .filterNotNull().forEach { name ->
                    state[name] = downloader.getFontFile(name)?.let { it.isFile && it.length() > 0 } == true
                }
        }
    }
    var downloading by remember { mutableStateOf<String?>(null) }
    var errorFont by remember { mutableStateOf<String?>(null) }
    return ReaderFontDownloads(downloaded, downloading, errorFont) { file, url ->
        if (downloading == null) {
            downloading = file
            errorFont = null
            scope.launch {
                try {
                    val result = downloader.downloadFont(file, url)
                    check(result.isFile && result.length() > 0)
                    downloaded[file] = true
                    availabilityChanged()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    errorFont = file
                } finally {
                    downloading = null
                }
            }
        }
    }
}

@Composable
private fun FontControls(
    draft: ReaderFormatting,
    onChange: (ReaderFormatting) -> Unit,
    downloads: ReaderFontDownloads,
) {
    var chinese by remember { mutableStateOf(false) }
    val enabled = LocalFormattingEnabled.current
    val names = if (chinese) EInkChineseFontPreference.names else EInkEnglishFontPreference.names
    val files = if (chinese) EInkChineseFontPreference.fontFileNames else EInkEnglishFontPreference.fontFileNames
    val urls = if (chinese) EInkChineseFontPreference.fontUrls else EInkEnglishFontPreference.fontUrls
    val selected = if (chinese) draft.chineseFont else draft.englishFont
    val language = if (chinese) "chinese" else "english"
    LazyColumn(
        flingBehavior = NoFormattingFling,
        modifier = Modifier.fillMaxSize().testTag("reader_formatting_font_list"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FormattingButton(stringResource(R.string.reader_formatting_english), Modifier.weight(1f)
                    .testTag("reader_formatting_font_language_english"), isSelected = !chinese, onClick = { chinese = false })
                FormattingButton(stringResource(R.string.reader_formatting_chinese), Modifier.weight(1f)
                    .testTag("reader_formatting_font_language_chinese"), isSelected = chinese, onClick = { chinese = true })
            }
        }
        item {
            Text(stringResource(R.string.reader_formatting_download_hint), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 4.dp))
        }
        itemsIndexed(names, key = { index, _ -> "$language-$index" }) { index, name ->
            val file = files[index]
            val url = urls[index]
            val available = file == null || downloads.available[file] == true
            val chosen = selected == index
            val status = when {
                downloads.downloading == file && file != null -> R.string.reader_formatting_downloading
                !available && chosen -> R.string.reader_formatting_font_missing_selected
                !available -> R.string.reader_formatting_not_downloaded
                file == null -> R.string.reader_formatting_system_font
                else -> R.string.reader_formatting_downloaded
            }
            val statusText = stringResource(status)
            Surface(color = Color.White, contentColor = Color.Black,
                border = BorderStroke(if (chosen) 2.dp else 1.dp, Color.Black), shape = RoundedCornerShape(4.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("reader_formatting_font_${language}_$index")
                            .semantics { this.selected = chosen; stateDescription = statusText }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                enabled = available && enabled, role = Role.RadioButton,
                                onClick = {
                                    onChange(if (chinese) draft.copy(chineseFont = index) else draft.copy(englishFont = index))
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                            Text(statusText, style = MaterialTheme.typography.labelSmall)
                        }
                        if (chosen) Text("✓", modifier = Modifier.padding(end = 4.dp), fontWeight = FontWeight.Bold)
                    }
                    if (!available && file != null && url != null) {
                        FormattingButton(
                            label = stringResource(if (downloads.downloading == file) R.string.reader_formatting_downloading else R.string.reader_formatting_download),
                            description = stringResource(R.string.reader_formatting_download_font, name),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                .testTag("reader_formatting_font_${language}_${index}_download"),
                            enabled = downloads.downloading == null,
                            onClick = { downloads.download(file, url) },
                        )
                    }
                    if (downloads.errorFont != null && downloads.errorFont == file) {
                        Text(stringResource(R.string.reader_formatting_download_failed),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Instant black/white feedback instead of ripples, selection animations or moving indicators. */
@Composable
private fun FormattingButton(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    isSelected: Boolean? = null,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val inverse = emphasized || isSelected == true
    val interactive = enabled && LocalFormattingEnabled.current
    Surface(
        modifier = modifier.heightIn(min = 48.dp)
            .semantics {
                if (description != null) contentDescription = description
                if (isSelected != null) selected = isSelected
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = interactive, role = if (isSelected != null) Role.Tab else Role.Button, onClick = onClick),
        color = if (inverse) Color.Black else Color.White,
        contentColor = if (inverse) Color.White else Color.Black,
        border = BorderStroke(if (interactive) 1.dp else .5.dp, Color.Black),
        shape = RoundedCornerShape(4.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (inverse) Color.White else Color.Black,
                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                fontWeight = if (inverse) FontWeight.Bold else FontWeight.Medium)
        }
    }
}
