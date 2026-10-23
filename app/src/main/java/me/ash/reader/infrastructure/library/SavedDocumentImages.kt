package me.ash.reader.infrastructure.library

import java.io.File
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Normal offline image rewriting only. This is not an HTML sanitizer or a WebView policy. */
internal object SavedDocumentImages {
    private const val ORIGINAL = "data-inkread-saved-source"
    private const val ALTERNATE = "data-inkread-saved-alternate-"
    internal const val MAX_CANDIDATES = 4
    private const val MAX_SOURCE_SET_LENGTH = 32 * 1024
    private const val MAX_SOURCE_SET_ENTRIES = 64
    private val lazyAttributes = listOf("data-src", "data-original", "data-lazy-src")

    /** One stable identity per picture, not one image for every responsive alternative. */
    fun sources(html: String, baseUrl: String): List<String> = candidateGroups(html, baseUrl).keys.toList()

    fun candidateGroups(html: String, baseUrl: String): Map<String, List<String>> {
        val groups = linkedMapOf<String, List<String>>()
        parse(html, baseUrl).select("img").forEach { image ->
            val candidates = candidates(image)
            val source = candidates.firstOrNull() ?: return@forEach
            groups[source] = (groups[source].orEmpty() + candidates).distinct().take(MAX_CANDIDATES)
        }
        return groups
    }

    fun retained(html: String, baseUrl: String, accepts: (String) -> Boolean): Map<String, String> =
        parse(html, baseUrl).select("img").mapNotNull { image ->
            val source = candidates(image).firstOrNull() ?: return@mapNotNull null
            val uri = runCatching { URI(image.attr("src")) }.getOrNull()
            val path = if (uri?.scheme == "file") runCatching { File(uri).absolutePath }.getOrNull() else null
            if (path != null && accepts(path)) source to path else null
        }.toMap()

    fun rewrite(html: String, baseUrl: String, retained: Map<String, String>): String {
        val document = parse(html, baseUrl)
        document.select("img").forEach { image ->
            val candidates = candidates(image)
            val original = candidates.firstOrNull()
            if (original != null) image.attr(ORIGINAL, original)
            // Retry metadata is inert. It must survive offline rewriting without a browser
            // fetching alternates or a retained file URL becoming the next download identity.
            (1 until MAX_CANDIDATES).forEach { image.removeAttr("$ALTERNATE$it") }
            candidates.drop(1).forEachIndexed { index, url -> image.attr("$ALTERNATE${index + 1}", url) }
            (listOf("src", "srcset", "data-srcset") + lazyAttributes).forEach(image::removeAttr)
            original?.let { retained[it] }?.let { image.attr("src", File(it).toURI().toString()) }
        }
        document.select("picture source").forEach { source ->
            listOf("srcset", "data-srcset", "src").forEach(source::removeAttr)
        }
        return document.body().html()
    }

    private fun parse(html: String, baseUrl: String): Document {
        val document = Jsoup.parseBodyFragment(html, baseUrl)
        // Preserve source-only editorial pictures before removing online alternatives.
        document.select("picture").filter { it.selectFirst("img") == null }.forEach { picture ->
            if (picture.select("source").take(8).any { sourceSet(it).isNotEmpty() }) picture.appendElement("img")
        }
        return document
    }

    private fun candidates(image: Element): List<String> {
        image.attr(ORIGINAL).takeIf { it.isNotBlank() }?.let { original ->
            // Keep legacy saved snapshots and their local assets stable, even when the
            // selection policy changes. Never reinterpret the retained src=file URL.
            return (listOf(original) + (1 until MAX_CANDIDATES).map { image.attr("$ALTERNATE$it") })
                .filter { it.isNotBlank() }.distinct().take(MAX_CANDIDATES)
        }
        val direct = (lazyAttributes + "src").mapNotNull { attribute ->
            image.attr(attribute).trim().takeIf { it.isNotBlank() }?.let { resolve(image, it) }
        }
        val responsive = image.parent()?.takeIf { it.tagName() == "picture" }?.select("source")
            ?.take(8)?.flatMap { sourceSet(it) }.orEmpty() + sourceSet(image)
        val available = (responsive + direct).filterNot { it.startsWith("data:", ignoreCase = true) }.distinct()
        // Reserve both the preferred lazy URL and ordinary src when present, even if
        // a srcset has dozens of variants. Either may be an authenticated proxy or a
        // format fallback, not another size; keep their URLs intact rather than unwrap them.
        val directFallbacks = direct.filterNot { it.startsWith("data:", ignoreCase = true) }
            .let { listOfNotNull(it.firstOrNull(), it.lastOrNull()).distinct() }
        val selected = (available.filterNot { it in directFallbacks }
            .take(MAX_CANDIDATES - directFallbacks.size) + directFallbacks).toSet()
        val bounded = available.filter { it in selected }.take(MAX_CANDIDATES)
        // Embedded/unsupported images still count as missing, not as an image-free page.
        return bounded.ifEmpty { (direct + responsive).take(1) }
    }

    private data class ResponsiveSource(val url: String, val scale: Double?)

    private fun sourceSet(element: Element): List<String> =
        listOf("data-srcset", "srcset").flatMap { attribute -> parseSourceSet(element, element.attr(attribute)) }
            .distinctBy { it.url }
            // Prefer a useful reading size, not a tiny first entry or an unbounded original.
            // Width and density sets use 1600w and 2x as their respective targets.
            .sortedWith(compareBy<ResponsiveSource> { if (it.scale == null) 2 else if (it.scale <= 1) 0 else 1 }
                .thenBy { it.scale?.let { scale -> if (scale <= 1) -scale else scale } ?: 0.0 })
            .take(MAX_CANDIDATES).map { it.url }

    private fun parseSourceSet(element: Element, raw: String): List<ResponsiveSource> {
        // Do not split URLs on commas: signed/proxied image URLs can contain commas.
        // As in HTML srcset, a URL ends at whitespace; trailing commas end a bare entry.
        val value = raw.take(MAX_SOURCE_SET_LENGTH)
        val result = mutableListOf<ResponsiveSource>()
        var position = 0
        var inspected = 0
        while (position < value.length && inspected++ < MAX_SOURCE_SET_ENTRIES) {
            while (position < value.length && (value[position].isWhitespace() || value[position] == ',')) position++
            val start = position
            while (position < value.length && !value[position].isWhitespace()) position++
            val token = value.substring(start, position)
            if (token.isEmpty()) break
            val bare = token.endsWith(',')
            val url = token.trimEnd(',')
            val descriptorStart = position
            if (!bare) while (position < value.length && value[position] != ',') position++
            val descriptor = if (bare) "" else value.substring(descriptorStart, position).trim()
            // A length cap must not invent a truncated URL or descriptor.
            if (raw.length > value.length && position == value.length) break
            val scale = when {
                descriptor.isEmpty() -> null
                descriptor.endsWith('w') -> descriptor.dropLast(1).toIntOrNull()?.takeIf { it > 0 }?.div(1600.0)
                descriptor.endsWith('x') -> descriptor.dropLast(1).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.div(2.0)
                else -> null
            }
            if (url.isNotEmpty() && (descriptor.isEmpty() || scale != null)) result += ResponsiveSource(resolve(element, url), scale)
        }
        return result
    }

    private fun resolve(element: Element, raw: String): String =
        runCatching { URI(element.baseUri()).resolve(raw).toString() }.getOrDefault(raw)
}
