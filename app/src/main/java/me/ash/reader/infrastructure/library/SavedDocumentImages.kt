package me.ash.reader.infrastructure.library

import java.io.File
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

/** Normal offline image rewriting only. This is not an HTML sanitizer or a WebView policy. */
internal object SavedDocumentImages {
    private const val ORIGINAL = "data-inkread-saved-source"
    fun sources(html: String, baseUrl: String): List<String> =
        parse(html, baseUrl).select("img").mapNotNull { source(it) }.distinct()

    fun retained(html: String, baseUrl: String, accepts: (String) -> Boolean): Map<String, String> =
        parse(html, baseUrl).select("img").mapNotNull { image ->
            val source = source(image) ?: return@mapNotNull null
            val uri = runCatching { URI(image.attr("src")) }.getOrNull()
            val path = if (uri?.scheme == "file") runCatching { File(uri).absolutePath }.getOrNull() else null
            if (path != null && accepts(path)) source to path else null
        }.toMap()

    fun rewrite(html: String, baseUrl: String, retained: Map<String, String>): String {
        val document = parse(html, baseUrl)
        document.select("img").forEach { image ->
            val original = source(image)
            if (original != null) image.attr(ORIGINAL, original)
            // Avoid network fallback or srcset overriding a retained local image.
            listOf("src", "srcset", "data-src", "data-original", "data-lazy-src", "data-srcset").forEach(image::removeAttr)
            original?.let { retained[it] }?.let { image.attr("src", File(it).toURI().toString()) }
        }
        document.select("picture source").forEach { it.removeAttr("srcset").removeAttr("src") }
        return document.body().html()
    }
    private fun parse(html: String, baseUrl: String): Document {
        val document = Jsoup.parseBodyFragment(html, baseUrl)
        // Preserve source-only editorial pictures as an ordinary img before removing online alternatives.
        document.select("picture").filter { it.selectFirst("img") == null }.forEach { picture ->
            picture.select("source").firstNotNullOfOrNull { sourceSet(it) }?.let { url ->
                picture.appendElement("img").attr(ORIGINAL, url)
            }
        }
        return document
    }
    private fun source(image: Element): String? {
        image.attr(ORIGINAL).takeIf { it.isNotBlank() }?.let { return it }
        for (attribute in listOf("data-src", "data-original", "data-lazy-src")) {
            val value = image.attr(attribute).trim()
            if (value.isNotBlank()) return image.absUrl(attribute).ifBlank { value }
        }
        val src = image.attr("src").trim()
        if (src.isNotBlank() && !src.startsWith("data:", ignoreCase = true)) return image.absUrl("src").ifBlank { src }
        sourceSet(image)?.let { return it }
        image.parent()?.takeIf { it.tagName() == "picture" }?.select("source")
            ?.firstNotNullOfOrNull { sourceSet(it) }?.let { return it }
        // Embedded/unsupported images remain counted as missing, never mistaken for an image-free page.
        return src.takeIf { it.isNotBlank() }
    }
    private fun sourceSet(element: Element): String? {
        val value = element.attr("srcset").ifBlank { element.attr("data-srcset") }
        if (value.isBlank()) return null
        val raw = value.substringBefore(',').trim().substringBefore(' ')
        return runCatching { URI(element.baseUri()).resolve(raw).toString() }.getOrDefault(raw)
    }
}
