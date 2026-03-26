package me.ash.reader.infrastructure.html

/**
 * Shared utility for classifying Unicode code points by language/script category.
 *
 * Used by both [LanguageTextWrapper] (WebView CSS-based dual font sizing) and
 * the native Compose renderer in HtmlToComposable (SpanStyle-based dual font sizing).
 */
object LanguageClassifier {

    const val CLASS_ZH = "lang-zh"
    const val CLASS_EN = "lang-en"

    /**
     * Determines if a Unicode code point is a CJK (Chinese/Japanese/Korean) character.
     *
     * Covers:
     * - CJK Unified Ideographs (U+4E00–U+9FFF)
     * - CJK Unified Ideographs Extension A (U+3400–U+4DBF)
     * - CJK Unified Ideographs Extension B (U+20000–U+2A6DF)
     * - CJK Compatibility Ideographs (U+F900–U+FAFF)
     * - CJK Radicals Supplement (U+2E80–U+2EFF)
     * - Kangxi Radicals (U+2F00–U+2FDF)
     * - CJK Symbols and Punctuation (U+3000–U+303F)
     * - Hiragana (U+3040–U+309F)
     * - Katakana (U+30A0–U+30FF)
     * - Bopomofo (U+3100–U+312F)
     * - Hangul Syllables (U+AC00–U+D7AF)
     * - Fullwidth punctuation and forms (U+FF00–U+FFEF)
     */
    fun isCJK(codePoint: Int): Boolean {
        return codePoint in 0x4E00..0x9FFF ||
                codePoint in 0x3400..0x4DBF ||
                codePoint in 0x20000..0x2A6DF ||
                codePoint in 0xF900..0xFAFF ||
                codePoint in 0x2E80..0x2EFF ||
                codePoint in 0x2F00..0x2FDF ||
                codePoint in 0x3000..0x303F ||
                codePoint in 0x3040..0x309F ||
                codePoint in 0x30A0..0x30FF ||
                codePoint in 0x3100..0x312F ||
                codePoint in 0xAC00..0xD7AF ||
                codePoint in 0xFF00..0xFFEF
    }

    /**
     * Determines if a Unicode code point is an emoji.
     *
     * Treats emoji as neutral (neither CJK nor English) so they don't get
     * absorbed into language runs.
     *
     * Covers:
     * - Miscellaneous Symbols and Pictographs (U+1F300–U+1F5FF)
     * - Emoticons (U+1F600–U+1F64F)
     * - Transport and Map Symbols (U+1F680–U+1F6FF)
     * - Supplemental Symbols and Pictographs (U+1F900–U+1F9FF)
     * - Symbols and Pictographs Extended-A (U+1FA00–U+1FA6F)
     * - Symbols and Pictographs Extended-B (U+1FA70–U+1FAFF)
     * - Mahjong Tiles (U+1F000–U+1F02F)
     * - Domino Tiles (U+1F030–U+1F09F)
     * - Playing Cards (U+1F0A0–U+1F0FF)
     * - Dingbats (U+2700–U+27BF)
     * - Variation Selectors (U+FE00–U+FE0F) — emoji presentation selectors
     * - Regional Indicator Symbols (U+1F1E0–U+1F1FF)
     */
    fun isEmoji(codePoint: Int): Boolean {
        return codePoint in 0x1F000..0x1FAFF ||
                codePoint in 0x2700..0x27BF ||
                codePoint in 0xFE00..0xFE0F ||
                codePoint in 0x1F1E0..0x1F1FF
    }

    /**
     * Checks if a code point is meaningful for language classification.
     *
     * Returns false for whitespace, ASCII punctuation, and emoji — these are
     * treated as neutral characters that attach to the adjacent language run.
     */
    fun isClassifiable(codePoint: Int): Boolean {
        if (Character.isWhitespace(codePoint)) return false
        if (isEmoji(codePoint)) return false
        // Common ASCII punctuation (not letters or digits)
        val ch = codePoint.toChar()
        if (codePoint < 0x80 && !ch.isLetterOrDigit()) return false
        return true
    }
}
