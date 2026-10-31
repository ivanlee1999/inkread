package me.ash.reader.infrastructure.rss

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Paths, queries and user-info may all contain mailbox access tokens. */
internal fun newsletterFeedUrlSummary(url: String): String =
    url.toHttpUrlOrNull()?.host?.let { "$it · private feed URL" } ?: "Private newsletter feed URL"
