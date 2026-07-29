package me.ash.reader.ui.page.home.reading

import java.net.URLDecoder

/** Synthetic origin the article document is served from via `shouldInterceptRequest`. */
internal const val EINK_DOCUMENT_ORIGIN = "https://inkread.local/"

internal const val HIT_LINK = "link"
internal const val HIT_IMAGE = "image"

internal data class EInkHitTestResult(val type: String, val url: String, val alt: String)

/**
 * Parse the `hitTest` return value handed back by `evaluateJavascript`.
 *
 * The JS side emits `type|percent-encoded-url|percent-encoded-alt`. Percent
 * encoding keeps the payload to URL-safe ASCII, so `evaluateJavascript`'s JSON
 * encoding of it adds nothing but the surrounding quotes — no nested-escaping
 * to unwind.
 *
 * Returns null for a miss or anything malformed, which falls back to turning
 * the page.
 */
internal fun parseHitTestResult(rawResult: String?): EInkHitTestResult? {
    val payload = rawResult?.trim()?.removeSurrounding("\"").orEmpty()
    if (payload.isEmpty() || payload == "null") return null

    val parts = payload.split('|')
    if (parts.size != 3) return null

    val type = parts[0]
    if (type != HIT_LINK && type != HIT_IMAGE) return null

    val url = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: return null
    // Only ever hand back real web URLs. An exotic scheme, or a relative
    // href/src that the DOM resolved against the local article document
    // (fragment links, relative image paths), is not something the reader
    // should try to open.
    if (!(url.startsWith("http://") || url.startsWith("https://"))) return null
    if (url.startsWith(EINK_DOCUMENT_ORIGIN)) return null

    val alt = runCatching { URLDecoder.decode(parts[2], "UTF-8") }.getOrNull().orEmpty()
    return EInkHitTestResult(type = type, url = url, alt = alt)
}
