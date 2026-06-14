package me.ash.reader.infrastructure.rss

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URL

private const val MAX_ICON_HTML_BYTES = 512L * 1024L
private const val MAX_ICON_BYTES = 512L * 1024L

class BestIconFinder(private val client: OkHttpClient) {

    private val defaultFormats = listOf("apple-touch-icon", "svg", "png", "ico", "gif", "jpg")

    suspend fun findBestIcon(siteUrl: String): String? {
        val url = normalizeUrl(siteUrl)
        val icons = fetchIcons(url)
        return selectBestIcon(icons)
    }

    private fun normalizeUrl(url: String): String {
        return if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "http://$url"
        } else {
            url
        }
    }

    private suspend fun fetchIcons(url: String): List<Icon> {
        val links = try {
            val html = fetchHtml(url)
            findIconLinks(url, html)
        } catch (e: Exception) {
            Log.w("RLog", "fetchIcons: $e")
            // Fallback to default icon paths if HTML fetch fails
            defaultIconUrls(url)
        }

        return links.mapNotNull { fetchIconDetails(it) }
    }

    private suspend fun fetchHtml(url: String): String {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            response.body.source().use {
                it.readStringLimited(
                    response.body.contentType()?.charset() ?: Charsets.UTF_8,
                    maxBytes = MAX_ICON_HTML_BYTES,
                )
            }
        }
    }

    private fun findIconLinks(baseUrl: String, html: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        val links = mutableListOf<String>()

        // Find apple-touch-icon
        links.addAll(doc.select("link[rel~=apple-touch-icon]").map { it.attr("abs:href") })

        // Find link rel="icon" and rel="shortcut icon"
        links.addAll(doc.select("link[rel~=icon]").map { it.attr("abs:href") })

        // Find meta property="og:image"
        doc.select("meta[property=og:image]").firstOrNull()?.attr("content")?.let {
            links.add(it)
        }

        // Add default favicon.ico if not already present
        val faviconUrl = URL(URL(baseUrl), "/favicon.ico").toString()
        if (faviconUrl !in links) {
            links.add(faviconUrl)
        }

        return links.distinct()
    }

    private fun defaultIconUrls(siteUrl: String): List<String> {
        val baseUrl = URL(siteUrl)
        return listOf(
            "/apple-touch-icon.png",
            "/apple-touch-icon-precomposed.png",
            "/favicon.ico"
        ).map { URL(baseUrl, it).toString() }
    }

    private fun fetchIconDetails(url: String): Icon? {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body.source().use {
                    it.readByteArrayLimited(MAX_ICON_BYTES)
                }.takeIf { it.isNotEmpty() } ?: return null

                val contentType = response.header("Content-Type") ?: ""
                val format = when {
                    url.contains("apple-touch-icon") -> "apple-touch-icon"
                    contentType.contains("svg") -> "svg"
                    contentType.contains("png") -> "png"
                    contentType.contains("ico") -> "ico"
                    contentType.contains("gif") -> "gif"
                    contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
                    else -> return null
                }

                return Icon(url, format, body.size)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun selectBestIcon(icons: List<Icon>): String? {
        return icons.sortedWith(compareBy(
            { defaultFormats.indexOf(it.format) },
            { -it.size }
        )).firstOrNull()?.url
    }

    data class Icon(val url: String, val format: String, val size: Int)
}
