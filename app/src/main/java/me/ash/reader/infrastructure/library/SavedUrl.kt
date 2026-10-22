package me.ash.reader.infrastructure.library

import java.net.URI
import java.util.Locale

/** Deliberately conservative: retain query order, tracking parameters, path case and escaping. */
internal fun normalizedSavedUrl(value: String): String {
    val raw = value.trim()
    require(raw.length in 1..8192) { "Enter a valid article URL" }
    val uri = runCatching { URI(raw) }.getOrNull()
        ?: throw IllegalArgumentException("Enter a valid article URL")
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") { "Use an HTTP or HTTPS article URL" }
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Enter a valid article URL" }
    val host = uri.host.lowercase(Locale.ROOT)
    val port = if (uri.port == -1 || scheme == "https" && uri.port == 443 || scheme == "http" && uri.port == 80) "" else ":${uri.port}"
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    return "$scheme://$host$port$path" + (uri.rawQuery?.let { "?$it" } ?: "")
}
