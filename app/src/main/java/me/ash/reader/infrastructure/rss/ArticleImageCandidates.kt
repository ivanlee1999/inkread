package me.ash.reader.infrastructure.rss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal const val ARTICLE_IMAGE_MAX_CANDIDATES = 6
internal const val ARTICLE_IMAGE_MAX_HTML_BYTES = 512L * 1024L

/** Editorial photographs only. No page is fetched until a composed photo needs an alternate. */
data class ArticleImageRequest(
    val articleUrl: String? = null,
    val candidates: List<String> = emptyList(),
)

internal fun articleImageRequest(storedImage: String?, savedHtml: String?, articleUrl: String, allowMetadataLookup: Boolean = true): Any? {
    // Keep established intranet/nonstandard-port feed photographs on the legacy image path.
    // This does not authorize probing their article pages or discovering private alternates.
    val existing = articleThumbnail(storedImage, savedHtml?.take(ARTICLE_IMAGE_MAX_HTML_BYTES.toInt()), articleUrl)
    if (existing != null && publicArticleUrl(existing) == null) return existing
    val candidates = rankedArticleImageCandidates(savedHtml, articleUrl, storedImage)
    val publicArticle = if (allowMetadataLookup) publicArticleUrl(articleUrl)?.toString() else null
    return if (candidates.isEmpty() && publicArticle == null) null
    else ArticleImageRequest(publicArticle, candidates)
}

/** Bounded, deterministic ranking. Feed images win; metadata precedes in-article photographs. */
internal fun rankedArticleImageCandidates(html: String?, baseUrl: String, storedImage: String? = null): List<String> {
    val candidates = linkedMapOf<String, Int>()
    var resolutionBase = baseUrl
    fun add(raw: String?, rank: Int) {
        val url = normalizeImageUrl(raw, resolutionBase)?.takeIf { publicArticleUrl(it) != null } ?: return
        if (isUnhelpfulArticleImage(url)) return
        if (candidates.size < 96 || candidates.containsKey(url)) {
            candidates[url] = maxOf(candidates[url] ?: 0, rank)
        }
    }
    add(storedImage, 100)
    if (html.isNullOrBlank()) return candidates.keys.take(ARTICLE_IMAGE_MAX_CANDIDATES)
    // Also bound saved content, which does not pass through the network byte limiter.
    val document = Jsoup.parse(html.take(ARTICLE_IMAGE_MAX_HTML_BYTES.toInt()), baseUrl)
    document.selectFirst("base[href]")?.attr("href")?.let { base ->
        normalizeImageUrl(base, baseUrl)?.takeIf { publicArticleUrl(it) != null }?.let { resolutionBase = it }
    }
    document.select("meta[property], meta[name]").take(128).forEach { meta ->
        when (meta.attr("property").ifBlank { meta.attr("name") }.lowercase()) {
            "og:image:secure_url" -> add(meta.attr("content"), 95)
            "og:image", "og:image:url" -> add(meta.attr("content"), 94)
            "twitter:image", "twitter:image:src" -> add(meta.attr("content"), 90)
        }
    }
    document.select("script[type=application/ld+json]").take(8).forEach { script ->
        if (script.data().length <= 128 * 1024) {
            val json = runCatching { Json.parseToJsonElement(script.data()) }.getOrNull()
            collectArticleJsonImages(json) { add(it, 85) }
        }
    }
    document.select("enclosure[url]").take(32).forEach { enclosure ->
        if (isImageEnclosure(enclosure.attr("url"), enclosure.attr("type"))) add(enclosure.attr("url"), 80)
    }
    val editorialRoots = document.select("article, main, [role=main]")
    val images = if (editorialRoots.isNotEmpty()) editorialRoots.select("img") else document.select("img")
    images.take(64).forEach { image ->
        if (!isTinyImage(image)) {
            listOf("data-src", "data-original", "data-lazy-src").forEach { add(image.attr(it), 72) }
            imageSourceSetUrls(image.attr("data-srcset")).asReversed().take(6).forEach { add(it, 71) }
            image.parent()?.takeIf { it.tagName() == "picture" }?.select("source")?.take(4)?.forEach { source ->
                imageSourceSetUrls(source.attr("srcset")).asReversed().take(6).forEach { add(it, 70) }
            }
            imageSourceSetUrls(image.attr("srcset")).asReversed().take(6).forEach { add(it, 69) }
            add(image.attr("src"), 68)
        }
    }
    return candidates.entries.sortedByDescending { it.value }.take(ARTICLE_IMAGE_MAX_CANDIDATES).map { it.key }
}

private fun isTinyImage(image: Element): Boolean {
    fun small(attribute: String) = image.attr(attribute).removeSuffix("px").toIntOrNull()?.let { it <= 64 } ?: false
    return small("width") || small("height") || image.attr("aria-hidden") == "true"
}

private fun isUnhelpfulArticleImage(url: String): Boolean {
    val path = publicArticleUrl(url)?.encodedPath.orEmpty().lowercase()
    return path.substringAfterLast('.').lowercase() in setOf("mp3", "m4a", "ogg", "mp4", "webm", "mov", "pdf") ||
        Regex("(?:^|[/_.-])(?:logo|favicon|avatar|sprite|spacer|blank|placeholder|pixel|tracking)(?:[/_.-]|$)").containsMatchIn(path)
}

private fun collectArticleJsonImages(root: JsonElement?, add: (String) -> Unit) {
    var visited = 0
    fun imageValue(value: JsonElement?, depth: Int = 0) {
        if (depth > 6 || ++visited > 512) return
        when (value) {
            is JsonPrimitive -> value.contentOrNull?.let(add)
            is JsonArray -> value.take(12).forEach { imageValue(it, depth + 1) }
            is JsonObject -> {
                imageValue(value["url"], depth + 1)
                imageValue(value["contentUrl"], depth + 1)
                imageValue(value["@id"], depth + 1)
            }
            else -> Unit
        }
    }
    fun visit(value: JsonElement?, depth: Int = 0) {
        if (depth > 8 || ++visited > 512) return
        when (value) {
            is JsonArray -> value.take(32).forEach { visit(it, depth + 1) }
            is JsonObject -> {
                val type = value["@type"]
                val names = if (type is JsonArray) type else listOfNotNull(type)
                if (names.any { (it as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/')?.endsWith("Article") == true }) {
                    imageValue(value["image"])
                    imageValue(value["thumbnailUrl"])
                }
                value.values.take(32).forEach { if (it is JsonObject || it is JsonArray) visit(it, depth + 1) }
            }
            else -> Unit
        }
    }
    visit(root)
}

/** Retry inputs are bounded even for callers constructing a request directly. */
internal fun articleImageAlternates(candidates: List<String>, attempted: Set<String> = emptySet()): List<String> =
    candidates.asSequence().filter { it !in attempted && publicArticleUrl(it) != null }
        .distinct().take((ARTICLE_IMAGE_MAX_CANDIDATES - attempted.size).coerceAtLeast(0)).toList()
