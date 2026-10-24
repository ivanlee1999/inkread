package me.ash.reader.infrastructure.rss

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Explicitly pasted text is an alternate source, never HTML or a browser DOM snapshot.
 * Keep this independent of extraction and of the reader's HTML sanitization boundary.
 */
internal object BrowserTextImport {
    const val MAX_UTF8_BYTES = 128 * 1024

    enum class ValidationError { EMPTY, TOO_LARGE, URL_ONLY }

    private val url = Regex("(?i)(?:https?://|www\\.)\\S+")

    fun validate(text: String): ValidationError? = when {
        // UTF-8 cannot use fewer bytes than UTF-16 code units for ordinary valid text.
        // This first check also avoids encoding an arbitrarily large clipboard paste.
        text.length > MAX_UTF8_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_UTF8_BYTES ->
            ValidationError.TOO_LARGE
        text.isBlank() -> ValidationError.EMPTY
        text.trim().split(Regex("\\s+")).all { url.matches(it.trim('<', '>')) } ->
            ValidationError.URL_ONLY
        else -> null
    }

    /** Invalid imports never enter the cache, even when called outside the preview UI. */
    fun toSafeHtml(text: String): String {
        val error = validate(text)
        require(error == null) {
            when (error) {
                ValidationError.EMPTY -> "Paste article text before importing."
                ValidationError.TOO_LARGE -> "Copied text exceeds the 128 KiB limit."
                ValidationError.URL_ONLY -> "Paste article text, not only a web address."
                null -> "Invalid browser text."
            }
        }
        val document = Document.createShell("")
        document.outputSettings().prettyPrint(false)
        // Build nodes, do not parse pasted text. Even apparent HTML is displayed literally.
        text.trim().replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n[\\t ]*\n+"))
            .forEach { paragraph ->
                val element = Element("p")
                paragraph.split('\n').forEachIndexed { index, line ->
                    if (index > 0) element.appendElement("br")
                    element.appendChild(TextNode(line))
                }
                document.body().appendChild(element)
            }
        return document.body().html()
    }
}
