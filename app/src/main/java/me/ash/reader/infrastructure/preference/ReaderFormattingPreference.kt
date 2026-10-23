package me.ash.reader.infrastructure.preference

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import me.ash.reader.ui.ext.DataStoreKey

/** Existing font/spacing keys remain authoritative; only the independent CJK size adds a key. */
object ReaderFormattingPreference {
    private val fontSize = intPreferencesKey(DataStoreKey.einkFontSize)
    private val chineseFontSize = intPreferencesKey(DataStoreKey.einkChineseFontSize)
    private val englishFont = intPreferencesKey(DataStoreKey.einkEnglishFont)
    private val chineseFont = intPreferencesKey(DataStoreKey.einkChineseFont)
    private val lineHeight = floatPreferencesKey(DataStoreKey.readingTextLineHeight)
    private val letterSpacing = floatPreferencesKey(DataStoreKey.readingTextLetterSpacing)
    private val wordSpacing = floatPreferencesKey(DataStoreKey.einkWordSpacing)
    private val alignment = intPreferencesKey(DataStoreKey.readingTextAlign)
    private val legacyPadding = intPreferencesKey(DataStoreKey.readingTextHorizontalPadding)
    private val marginLeft = intPreferencesKey(DataStoreKey.einkMarginLeft)
    private val marginRight = intPreferencesKey(DataStoreKey.einkMarginRight)
    private val marginTop = intPreferencesKey(DataStoreKey.einkMarginTop)
    private val marginBottom = intPreferencesKey(DataStoreKey.einkMarginBottom)
    private val textDarkness = floatPreferencesKey(DataStoreKey.einkTextDarkness)
    private val textWeight = intPreferencesKey(DataStoreKey.einkTextWeight)
    private val paragraphGap = floatPreferencesKey(DataStoreKey.einkParagraphGap)
    private val paragraphIndent = floatPreferencesKey(DataStoreKey.einkParagraphIndent)
    private val imageContrast = floatPreferencesKey(DataStoreKey.einkImageContrast)
    private val imageGrayscale = booleanPreferencesKey(DataStoreKey.einkImageGrayscale)

    /** Read-time migration is non-mutating. Each missing margin inherits the old all-side inset. */
    fun fromPreferences(preferences: Preferences): ReaderFormatting {
        val defaults = ReaderFormatting.Default
        val padding = preferences[legacyPadding] ?: defaults.marginLeft
        return ReaderFormatting(
            fontSize = preferences[fontSize] ?: defaults.fontSize,
            chineseFontSize = preferences[chineseFontSize] ?: preferences[fontSize] ?: defaults.chineseFontSize,
            englishFont = preferences[englishFont] ?: defaults.englishFont,
            chineseFont = preferences[chineseFont] ?: defaults.chineseFont,
            lineHeight = preferences[lineHeight] ?: defaults.lineHeight,
            marginLeft = preferences[marginLeft] ?: padding,
            marginRight = preferences[marginRight] ?: padding,
            marginTop = preferences[marginTop] ?: padding,
            marginBottom = preferences[marginBottom] ?: padding,
            textDarkness = preferences[textDarkness] ?: defaults.textDarkness,
            // Legacy readingTextBold belongs to the scrolling reader and is intentionally untouched.
            textWeight = preferences[textWeight] ?: defaults.textWeight,
            letterSpacing = preferences[letterSpacing] ?: defaults.letterSpacing,
            wordSpacing = preferences[wordSpacing] ?: defaults.wordSpacing,
            paragraphGap = preferences[paragraphGap] ?: defaults.paragraphGap,
            paragraphIndent = preferences[paragraphIndent] ?: defaults.paragraphIndent,
            alignment = ReaderTextAlignment.fromValue(preferences[alignment]),
            imageContrast = preferences[imageContrast] ?: defaults.imageContrast,
            imageGrayscale = preferences[imageGrayscale] ?: defaults.imageGrayscale,
        ).clamped()
    }

    internal fun writeTo(preferences: MutablePreferences, value: ReaderFormatting) {
        val format = value.clamped()
        preferences[fontSize] = format.fontSize
        preferences[chineseFontSize] = format.chineseFontSize
        preferences[englishFont] = format.englishFont
        preferences[chineseFont] = format.chineseFont
        preferences[lineHeight] = format.lineHeight
        preferences[letterSpacing] = format.letterSpacing
        preferences[wordSpacing] = format.wordSpacing
        preferences[alignment] = format.alignment.persistedValue
        preferences[marginLeft] = format.marginLeft
        preferences[marginRight] = format.marginRight
        preferences[marginTop] = format.marginTop
        preferences[marginBottom] = format.marginBottom
        preferences[textDarkness] = format.textDarkness
        preferences[textWeight] = format.textWeight
        preferences[paragraphGap] = format.paragraphGap
        preferences[paragraphIndent] = format.paragraphIndent
        preferences[imageContrast] = format.imageContrast
        preferences[imageGrayscale] = format.imageGrayscale
        // A symmetric setting can be represented by the scrolling reader. An asymmetric setting
        // cannot: preserve its existing padding instead of arbitrarily choosing one side.
        if (format.marginLeft == format.marginRight) preferences[legacyPadding] = format.marginLeft
    }

    /** The old single-size/quick A controls move both scripts together, retaining their offset. */
    internal fun writeCombinedFontSize(preferences: MutablePreferences, value: Int) {
        val before = fromPreferences(preferences)
        val english = value.coerceIn(ReaderFormatting.FontSizeRange)
        preferences[fontSize] = english
        preferences[chineseFontSize] = (before.chineseFontSize + english - before.fontSize)
            .coerceIn(ReaderFormatting.FontSizeRange)
    }

    /** Compatibility for the existing horizontal-padding settings control, in the same transaction. */
    internal fun writeHorizontalPadding(preferences: MutablePreferences, value: Int) {
        val before = fromPreferences(preferences)
        val padding = value.coerceIn(ReaderFormatting.MarginRange)
        // Materialize vertical fallbacks BEFORE changing the old shared key.
        preferences[marginTop] = before.marginTop
        preferences[marginBottom] = before.marginBottom
        // Preserve the scrolling reader's established input semantics; only e-ink bounds its inset.
        preferences[legacyPadding] = value
        preferences[marginLeft] = padding
        preferences[marginRight] = padding
    }
}

/** One DataStore transaction, one observable snapshot. Exceptions propagate so Apply can stay open. */
suspend fun DataStore<Preferences>.saveReaderFormatting(value: ReaderFormatting): ReaderFormatting {
    val format = value.clamped()
    edit { ReaderFormattingPreference.writeTo(it, format) }
    return format
}

internal suspend fun DataStore<Preferences>.saveReaderHorizontalPadding(value: Int) {
    edit { ReaderFormattingPreference.writeHorizontalPadding(it, value) }
}

internal suspend fun DataStore<Preferences>.saveCombinedReaderFontSize(value: Int) {
    edit { ReaderFormattingPreference.writeCombinedFontSize(it, value) }
}
