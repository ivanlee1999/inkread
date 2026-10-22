package me.ash.reader.ui.page.library

import java.net.URI

/** Pure routing policy: a generic share is never silently treated as a feed subscription. */
internal enum class LinkCaptureMode { CHOOSE, SUBSCRIBE, SAVE }

internal fun externalLinkCaptureMode(action: String?, mimeType: String?): LinkCaptureMode? =
    when (action) {
        "android.intent.action.SEND" -> LinkCaptureMode.CHOOSE
        "android.intent.action.VIEW" -> if (mimeType?.substringBefore(';')?.trim()?.lowercase() in
            setOf("text/xml", "application/xml", "application/rss+xml", "application/atom+xml", "application/json"))
            LinkCaptureMode.SUBSCRIBE else LinkCaptureMode.CHOOSE
        else -> null
    }

internal data class SharedLinkInput(val urls: List<String>, val titleHint: String?) {
    val requiresSelection: Boolean get() = urls.size > 1
}

/** Do not hand concatenated prose, titles or multiple links to either persistence or RSS. */
internal fun parseSharedLinks(text: String, titleHint: String? = null): SharedLinkInput {
    val matches = Regex("https?://[^\\s<>\\u0000-\\u0020]+", RegexOption.IGNORE_CASE)
        .findAll(text).toList()
    val urls = matches.mapNotNull { match ->
        var candidate = match.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', '’', '”')
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            while (candidate.endsWith(close) && candidate.count { it == close } > candidate.count { it == open }) {
                candidate = candidate.dropLast(1)
            }
        }
        runCatching { URI(candidate) }.getOrNull()?.takeIf {
            it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
        }?.let { candidate }
    }.distinct()
    val title = titleHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (urls.size == 1) matches.fold(text) { remaining, match -> remaining.replace(match.value, "") }
            .trim().trim('-', '–', '|', ':').trim().takeIf { it.isNotEmpty() } else null
    return SharedLinkInput(urls, title?.take(512))
}

/** Stable identity wins over a stale page number after deletion or responsive reflow. */
internal fun savedPageForAnchor(pageIds: List<List<String>>, anchorId: String?, previousPage: Int): Int =
    pageIds.indexOfFirst { anchorId != null && anchorId in it }.takeIf { it >= 0 }
        ?: previousPage.coerceIn(0, (pageIds.size - 1).coerceAtLeast(0))
