package me.ash.reader.infrastructure.rss

import com.rometools.rome.feed.synd.SyndFeed
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID

/** Exact source links only: never substitute a newer entry, summary, or fuzzy title match. */
internal fun originalFeedBody(feed: SyndFeed, sourceUrl: String): String {
    val entry = feed.entries.filter { it.link == sourceUrl }.singleOrNull()
        ?: throw IOException("Original feed content is unavailable for this article")
    val html = entry.contents.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.value }
        ?: entry.description?.value
    if (html.isNullOrBlank()) throw IOException("Original feed content is unavailable for this article")
    boundedFeedBodyBytes(html)
    return html
}

/** Conservative automatic-fetch consent guard; explicit Original selection may read this feed. */
internal fun isPrivateOriginalFeedUrl(value: String): Boolean {
    val url = publicArticleUrl(value) ?: return true
    if (url.fragment != null) return true
    if (url.queryParameterNames.any { name ->
            name.lowercase() !in setOf("feed", "format", "type", "output") ||
                url.queryParameterValues(name).any { it?.lowercase() !in setOf("rss", "rss2", "atom", "xml") }
        }) return true
    var path = url.encodedPath
    repeat(2) {
        path = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrElse { return true }
    }
    if (path.any { it == '%' || it == ';' || it == '=' }) return true
    return path.split('/').filter { it.isNotEmpty() }.any { segment ->
        segment.substringBefore('.').lowercase() in setOf("private", "token", "secret", "auth", "access-token", "access_token", "api-key", "api_key", "key", "personal", "subscriber", "password") ||
            runCatching { UUID.fromString(segment) }.isSuccess ||
            segment.length >= 24 && segment.all { it.isLetterOrDigit() || it in "-._=" } &&
                (segment.any(Char::isDigit) && segment.any(Char::isLowerCase) && segment.any(Char::isUpperCase) ||
                    segment.length >= 32 && segment.any(Char::isDigit) && !segment.contains('-') ||
                    segment.count { it == '.' } == 2)
    }
}
