package me.ash.reader.infrastructure.library

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.html.ModernReadability
import me.ash.reader.infrastructure.html.ArticleExtractionConcurrency
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.infrastructure.html.ArticleExtractionPreparation
import me.ash.reader.infrastructure.html.ArticleRequiresBrowserException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ash.reader.infrastructure.html.readHtmlLimited
import me.ash.reader.infrastructure.rss.articleImageHttpClient
import me.ash.reader.infrastructure.rss.publicArticleUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reuses the existing bounded public-article network client and ordinary readability parser. */
internal class PublicSavedArticleDownloader(private val limits: SavedStorageLimits) : SavedArticleDownloader {
    private val pageClient = articleImageHttpClient(limits.maxHtmlBytes)
    private val imageClient = articleImageHttpClient(limits.maxImageBytes)
    override suspend fun extract(url: String, titleHint: String?): SavedExtractedArticle = withContext(Dispatchers.IO) {
        val request = request(url).header("Accept", "text/html,application/xhtml+xml").build()
        val page = pageClient.newCall(request).readResponse { response ->
            val contentType = response.body.contentType()
            if (contentType != null && contentType.subtype !in setOf("html", "xhtml+xml")) throw IOException("Article is not an HTML page")
            response.request.url.toString() to response.body.readHtmlLimited(limits.maxHtmlBytes)
        }
        val jobContext = currentCoroutineContext()
        ArticleExtractionConcurrency.permit.withPermit {
            extractSavedArticle(page.second, page.first, titleHint, cancellationCheck = { jobContext.ensureActive() })
        }
    }
    override suspend fun fetchImage(url: String): ByteArray = withContext(Dispatchers.IO) {
        imageClient.newCall(request(url).header("Accept", "image/*").build()).readResponse { response ->
            val type = response.body.contentType()
            if (type?.type != "image" || type.subtype !in setOf("jpeg", "jpg", "png", "webp", "gif", "avif")) {
                throw IOException("Unsupported article image")
            }
            response.body.bytes().also { if (it.isEmpty()) throw IOException("Empty article image") }
        }
    }
    private fun request(url: String): Request.Builder {
        val publicUrl = publicArticleUrl(url) ?: throw IOException("Only public article pages can be downloaded")
        return Request.Builder().url(publicUrl).header("User-Agent", "InkRead-SavedLibrary/1.0")
    }
}

internal fun extractSavedArticle(html: String, url: String, titleHint: String?, cancellationCheck: () -> Unit = {}): SavedExtractedArticle {
    val document = Jsoup.parse(html, url)
    fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        document.selectFirst("meta[property='$key'], meta[name='$key']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
    }
    val title = (meta("og:title", "twitter:title") ?: document.title().takeIf { it.isNotBlank() }
        ?: titleHint?.takeIf { it.isNotBlank() } ?: publicArticleUrl(url)?.host ?: "Saved article").take(512)
    val prepared = ArticleExtractionPreparation.prepareOrThrow(html, url)
    val extracted = ModernReadability().extract(prepared, url, checkCancelled = cancellationCheck)
        ?: throw IOException("No readable article found")
    val article = Jsoup.parseBodyFragment(extracted.content, url).body()
    if (article.text().isBlank()) throw IOException("No readable article found")
    article.selectFirst("h1")?.takeIf { it.text() == title }?.remove()
    return SavedExtractedArticle(
        // Resolve ordinary relative images against the final response URL before retaining the
        // originally saved URL as the item's identity (redirects must not silently change dedup).
        html = SavedDocumentImages.rewrite(article.outerHtml(), url, emptyMap()), title = title,
        author = meta("author", "article:author", "byl")?.take(256),
        sourceName = (meta("og:site_name") ?: publicArticleUrl(url)?.host)?.take(256),
        excerpt = (meta("description", "og:description") ?: article.text()).take(280),
    )
}

private suspend fun <T> Call.readResponse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use {
                    if (it.code == 401 || it.code == 403) throw ArticleRequiresBrowserException(ArticleExtractionPreparation.AccessReason.ACCESS_GATE)
                    if (!it.isSuccessful) throw IOException("Article download failed (HTTP ${it.code})")
                    read(it)
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
