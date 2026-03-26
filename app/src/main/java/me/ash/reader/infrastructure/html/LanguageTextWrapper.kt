package me.ash.reader.infrastructure.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

/**
 * Utility that traverses HTML text nodes and wraps runs of Chinese (CJK) vs Latin/English
 * characters in `<span class="lang-zh">` / `<span class="lang-en">` elements.
 *
 * This enables CSS-based dual font sizing for Chinese and English text in WebView rendering.
 *
 * Language classification is delegated to [LanguageClassifier].
 */
object LanguageTextWrapper {

    /**
     * Wraps text content in an HTML string with language-specific span elements.
     *
     * @param html The HTML content string to process
     * @return The processed HTML string with language spans added
     */
    fun wrapHtml(html: String): String {
        if (html.isBlank()) return html

        val doc: Document = Jsoup.parseBodyFragment(html)
        doc.outputSettings()
            .outline(false)
            .indentAmount(0)
            .prettyPrint(false)

        wrapElement(doc.body())

        return doc.body().html()
    }

    /**
     * Recursively processes an element, wrapping text nodes with language spans.
     */
    private fun wrapElement(element: Element) {
        // Don't process inside script, style, code, or pre elements
        val skipTags = setOf("script", "style", "code", "pre", "textarea")
        if (element.tagName().lowercase() in skipTags) return

        // Process child nodes (iterate over a copy since we'll be modifying the list)
        val children = element.childNodes().toList()
        for (child in children) {
            when (child) {
                is TextNode -> {
                    val text = child.wholeText
                    // Preserve blank text nodes (e.g. trailing "\n") as-is without wrapping
                    if (text.isBlank()) {
                        // intentionally left in place — do not skip or remove
                    } else {
                        val spans = splitByLanguage(text)
                        if (spans.size <= 1 && spans.firstOrNull()?.second == null) {
                            // All one language or not classifiable — no wrapping needed for single-lang nodes
                            // But still wrap if we can classify
                            val singleSpan = spans.firstOrNull()
                            if (singleSpan != null && singleSpan.second != null) {
                                val spanElement = Element(Tag.valueOf("span"), "")
                                spanElement.addClass(singleSpan.second!!)
                                spanElement.appendText(singleSpan.first)
                                child.replaceWith(spanElement)
                            }
                        } else {
                            // Multiple runs: replace the text node with wrapped spans
                            // We need a container fragment - use a temporary holder
                            val fragment = Element(Tag.valueOf("span"), "")
                            fragment.addClass("lang-wrapper")

                            for ((runText, langClass) in spans) {
                                if (langClass != null) {
                                    val spanElement = Element(Tag.valueOf("span"), "")
                                    spanElement.addClass(langClass)
                                    spanElement.appendText(runText)
                                    fragment.appendChild(spanElement)
                                } else {
                                    // Whitespace/punctuation/emoji that couldn't be classified - keep as text
                                    fragment.appendText(runText)
                                }
                            }

                            child.replaceWith(fragment)
                        }
                    }
                }
                is Element -> {
                    // Don't wrap elements that already have lang-zh or lang-en
                    if (!child.hasClass(LanguageClassifier.CLASS_ZH) && !child.hasClass(LanguageClassifier.CLASS_EN)) {
                        wrapElement(child)
                    }
                }
            }
        }
    }

    /**
     * Splits text into runs of CJK, non-CJK (English/Latin), and neutral (whitespace/punctuation/emoji) characters.
     *
     * @return List of pairs: (text, cssClass) where cssClass is "lang-zh", "lang-en", or null for neutral
     */
    private fun splitByLanguage(text: String): List<Pair<String, String?>> {
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<Triple<StringBuilder, String?, Boolean>>() // text, class, isNeutral
        var currentBuilder = StringBuilder()
        var currentClass: String? = null
        var isCurrentNeutral = true

        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)

            if (!LanguageClassifier.isClassifiable(codePoint)) {
                // Neutral character (whitespace, punctuation, emoji) - append to current run
                currentBuilder.appendCodePoint(codePoint)
            } else {
                val charClass = if (LanguageClassifier.isCJK(codePoint)) LanguageClassifier.CLASS_ZH else LanguageClassifier.CLASS_EN
                if (isCurrentNeutral) {
                    // First classifiable char in current neutral run - assign this class
                    currentClass = charClass
                    isCurrentNeutral = false
                    currentBuilder.appendCodePoint(codePoint)
                } else if (charClass == currentClass) {
                    // Same language - continue
                    currentBuilder.appendCodePoint(codePoint)
                } else {
                    // Language switch - save current run and start new one
                    if (currentBuilder.isNotEmpty()) {
                        result.add(Triple(currentBuilder, currentClass, false))
                    }
                    currentBuilder = StringBuilder()
                    currentClass = charClass
                    isCurrentNeutral = false
                    currentBuilder.appendCodePoint(codePoint)
                }
            }

            i += charCount
        }

        // Add remaining text
        if (currentBuilder.isNotEmpty()) {
            result.add(Triple(currentBuilder, currentClass, isCurrentNeutral))
        }

        // Assign neutral-only runs to adjacent language, then convert to output format
        // For neutral-only entries, try to merge with previous or next classified run
        return result.map { (sb, cls, _) ->
            Pair(sb.toString(), cls)
        }
    }
}
