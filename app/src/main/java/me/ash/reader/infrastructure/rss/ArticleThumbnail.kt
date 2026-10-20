package me.ash.reader.infrastructure.rss

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** A saved article can recover its photograph without fetching or rewriting the article. */
internal fun articleThumbnail(storedImage: String?, savedHtml: String?, articleUrl: String): String? {
    // Persisted values also came from publishers. Do not trust local URI schemes, or the
    // obvious audio/video enclosures that earlier versions saved as article photographs.
    val stored = normalizeImageUrl(storedImage, articleUrl)?.takeUnless { url ->
        url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringAfterLast('.')?.lowercase() in
            NON_IMAGE_EXTENSIONS
    }
    return stored ?: findHtmlThumbnail(savedHtml, articleUrl)
}

/** Keep absolute URLs intact, including signed query strings; resolve only relative references. */
internal fun normalizeImageUrl(value: String?, baseUrl: String? = null): String? {
    val candidate = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: return null
    if (candidate.any { it <= '\u001f' || it == '\u007f' }) return null
    val absolute = candidate.toHttpUrlOrNull()
    if (absolute != null) return candidate
    // Never reinterpret an unsupported scheme (notably data:) as a relative path.
    if (IMAGE_URL_SCHEME.containsMatchIn(candidate)) return null
    return baseUrl?.toHttpUrlOrNull()?.resolve(candidate)?.toString()
}

internal fun isImageEnclosure(url: String?, type: String?): Boolean {
    val mime = type?.substringBefore(';')?.trim().orEmpty()
    if (mime.startsWith("image/", ignoreCase = true)) return true
    if (mime.isNotEmpty() && !mime.equals("application/octet-stream", ignoreCase = true)) return false
    return url?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('.')
        ?.lowercase() in IMAGE_EXTENSIONS
}

internal fun findHtmlThumbnail(html: String?, baseUrl: String? = null): String? {
    if (html.isNullOrBlank()) return null
    val document = Jsoup.parseBodyFragment(html, baseUrl.orEmpty())
    document.select("enclosure[url]").forEach { enclosure ->
        if (isImageEnclosure(enclosure.attr("url"), enclosure.attr("type"))) {
            normalizeImageUrl(enclosure.attr("url"), baseUrl)?.let { return it }
        }
    }
    document.select("img").forEach { image ->
        // Publishers often leave a tiny placeholder in src and the photograph in data-src.
        IMAGE_LAZY_SOURCE_ATTRIBUTES.forEach { attribute ->
            normalizeImageUrl(image.attr(attribute), baseUrl)?.let { return it }
        }
        imageFromSourceSet(image, baseUrl, listOf("data-srcset"))?.let { return it }
        normalizeImageUrl(image.attr("src"), baseUrl)?.let { return it }
        imageFromSourceSet(image, baseUrl)?.let { return it }
        image.parent()?.takeIf { it.tagName().equals("picture", ignoreCase = true) }
            ?.select("source")?.forEach { source ->
                imageFromSourceSet(source, baseUrl)?.let { return it }
            }
    }
    return null
}

private fun imageFromSourceSet(element: Element, baseUrl: String?, attributes: List<String> = listOf("srcset")): String? {
    for (attribute in attributes) {
        for (candidate in imageSourceSetUrls(element.attr(attribute))) {
            normalizeImageUrl(candidate, baseUrl)?.let { return it }
        }
    }
    return null
}

/** Commas inside URLs are valid. Split descriptors, not the signed URL token itself. */
internal fun imageSourceSetUrls(sourceSet: String): List<String> {
    val urls = mutableListOf<String>()
    var position = 0
    while (position < sourceSet.length) {
        while (position < sourceSet.length && (sourceSet[position].isWhitespace() || sourceSet[position] == ',')) position++
        val start = position
        while (position < sourceSet.length && !sourceSet[position].isWhitespace()) position++
        val token = sourceSet.substring(start, position)
        if (token.isEmpty()) break
        if (token.endsWith(',')) {
            urls += token.trimEnd(',')
        } else {
            val descriptorStart = position
            while (position < sourceSet.length && sourceSet[position] != ',') position++
            val descriptor = sourceSet.substring(descriptorStart, position).trim()
            if (descriptor.isEmpty() || IMAGE_SOURCE_DESCRIPTOR.matches(descriptor)) urls += token
        }
    }
    return urls
}

private val IMAGE_LAZY_SOURCE_ATTRIBUTES = listOf("data-src", "data-original", "data-lazy-src")
private val NON_IMAGE_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac", "mp4", "m4v", "webm", "mov", "avi", "mkv", "pdf")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "svg", "bmp", "heic", "heif")
private val IMAGE_URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val IMAGE_SOURCE_DESCRIPTOR = Regex("(?:[1-9][0-9]*w|(?:[0-9]*\\.)?[0-9]+x)")
