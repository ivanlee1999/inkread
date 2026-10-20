package me.ash.reader.infrastructure.rss

import android.content.Context
import android.util.Log
import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import com.rometools.modules.mediarss.types.UrlReference
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndImageImpl
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.io.ByteArrayInputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.Charset
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.ui.ext.currentAccountId
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.executeAsync
import okio.IOException
import okio.Buffer
import okio.BufferedSource
import org.jsoup.Jsoup

private const val FULL_CONTENT_MAX_BYTES = 2L * 1024L * 1024L
private const val FULL_CONTENT_CHARSET_SNIFF_BYTES = 64L * 1024L
private const val READ_BUFFER_BYTES = 8L * 1024L

/** Some operations on RSS. */
class RssHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
) {

    data class SearchFeedResult(
        val feed: SyndFeed,
        val feedLink: String,
    )

    @Throws(Exception::class)
    suspend fun searchFeed(feedLink: String): SearchFeedResult {
        return withContext(ioDispatcher) {
            val (directBody, directHttpContentType, directFeedLink) = response(okHttpClient, feedLink).use {
                if (!it.isSuccessful) throw IOException(it.message)
                Triple(
                    it.body.bytes(),
                    toHttpContentType(it.header("Content-Type")),
                    it.request.url.toString(),
                )
            }
            val parsedDirectFeed = runCatching { parseFeed(directBody, directHttpContentType) }.getOrNull()
            val (feed, resolvedFeedLink) =
                if (parsedDirectFeed != null) parsedDirectFeed to directFeedLink
                else {
                    val discoveredFeedLink = discoverFeedLink(directFeedLink, directBody)
                        ?: throw IOException("Unable to detect RSS feed URL")
                    response(okHttpClient, discoveredFeedLink).use {
                        if (!it.isSuccessful) throw IOException(it.message)
                        parseFeed(it.body.bytes(), toHttpContentType(it.header("Content-Type"))) to
                            it.request.url.toString()
                    }
                }

            feed.also {
                it.icon = SyndImageImpl()
                it.icon.link = queryRssIconLink(resolvedFeedLink)
                it.icon.url = it.icon.link
            }

            SearchFeedResult(feed = feed, feedLink = resolvedFeedLink)
        }
    }

    private fun toHttpContentType(contentType: String?): String =
        contentType?.let {
            if (it.contains("charset=", ignoreCase = true)) it else "$it; charset=UTF-8"
        } ?: "text/xml; charset=UTF-8"

    private fun parseFeed(body: ByteArray, httpContentType: String): SyndFeed =
        ByteArrayInputStream(body).use { inputStream ->
            SyndFeedInput().build(XmlReader(inputStream, httpContentType))
        }

    private fun discoverFeedLink(pageUrl: String, body: ByteArray): String? {
        val document = Jsoup.parse(String(body, Charsets.UTF_8), pageUrl)
        val links = document.select("head link[rel~=(?i)alternate][href]")
        val preferred =
            links.firstOrNull {
                val type = it.attr("type").lowercase(Locale.ROOT)
                type == "application/rss+xml" ||
                    type == "application/atom+xml" ||
                    type == "application/rdf+xml"
            }
        val fallback = links.firstOrNull()
        return (preferred ?: fallback)?.absUrl("href")?.takeIf { it.isNotBlank() }
    }

    @Throws(Exception::class)
    suspend fun parseFullContent(link: String, title: String): String {
        return withContext(ioDispatcher) {
            response(okHttpClient, link).use { response ->
                if (!response.isSuccessful) throw IOException(response.message)

                val responseBody = response.body
                val charset = responseBody.contentType()?.charset()
                val content =
                    responseBody.source().use {
                        if (charset != null) {
                            return@use it.readStringLimited(charset)
                        }

                        val charsetFromMeta =
                            it.peekCharsetFromMeta(link)

                        it.readStringLimited(charsetFromMeta)
                    }

                val articleContent = Readability.parseToElement(content, link)
                articleContent?.let {
                    val h1Element = articleContent.selectFirst("h1")
                    if (h1Element != null && h1Element.hasText() && h1Element.text() == title) {
                        h1Element.remove()
                    }
                    articleContent.toString()
                } ?: throw IOException("articleContent is null")
            }
        }
    }

    suspend fun queryRssXml(
        feed: Feed,
        latestLink: String?,
        preDate: Date = Date(),
    ): List<Article> =
        try {
            val accountId = context.currentAccountId
            response(okHttpClient, feed.url).use { response ->
                val contentType = response.header("Content-Type")

                val httpContentType =
                    contentType?.let {
                        if (it.contains("charset=", ignoreCase = true)) it
                        else "$it; charset=UTF-8"
                    } ?: "text/xml; charset=UTF-8"

                response.body.byteStream().use { inputStream ->
                    SyndFeedInput()
                        .apply { isPreserveWireFeed = true }
                        .build(XmlReader(inputStream, httpContentType))
                        .entries
                        .asSequence()
                        .takeWhile { latestLink == null || latestLink != it.link }
                        .map { buildArticleFromSyndEntry(feed, accountId, it, preDate) }
                        .toList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RLog", "queryRssXml[${feed.name}]: ${e.message}")
            listOf()
        }

    fun buildArticleFromSyndEntry(
        feed: Feed,
        accountId: Int,
        syndEntry: SyndEntry,
        preDate: Date = Date(),
    ): Article {
        val desc = syndEntry.description?.value
        val content =
            syndEntry.contents
                .takeIf { it.isNotEmpty() }
                ?.let { it.joinToString("\n") { it.value } }
        //        Log.i(
        //            "RLog",
        //            "request rss:\n" +
        //                    "name: ${feed.name}\n" +
        //                    "feedUrl: ${feed.url}\n" +
        //                    "url: ${syndEntry.link}\n" +
        //                    "title: ${syndEntry.title}\n" +
        //                    "desc: ${desc}\n" +
        //                    "content: ${content}\n"
        //        )
        return Article(
            id = accountId.spacerDollar(UUID.randomUUID().toString()),
            accountId = accountId,
            feedId = feed.id,
            date =
                (syndEntry.publishedDate ?: syndEntry.updatedDate)?.takeIf { !it.isFuture(preDate) }
                    ?: preDate,
            title = syndEntry.title.decodeHTML() ?: feed.name,
            author = syndEntry.author,
            rawDescription = content ?: desc ?: "",
            shortDescription = Readability.parseToText(desc ?: content, syndEntry.link).take(280),
            //            fullContent = content,
            img = findThumbnail(syndEntry, syndEntry.link ?: feed.url),
            link = syndEntry.link ?: "",
            updateAt = preDate,
        )
    }

    fun findThumbnail(syndEntry: SyndEntry, baseUrl: String? = syndEntry.link): String? {
        syndEntry.enclosures.orEmpty().forEach { enclosure ->
            if (isImageEnclosure(enclosure.url, enclosure.type)) {
                normalizeImageUrl(enclosure.url, baseUrl)?.let { return it }
            }
        }

        val mediaModule = syndEntry.getModule(MediaModule.URI) as? MediaEntryModule
        if (mediaModule != null) {
            findThumbnail(mediaModule, baseUrl)?.let { return it }
        }

        return syndEntry.contents.orEmpty().firstNotNullOfOrNull { findThumbnail(it.value, baseUrl) }
            ?: findThumbnail(syndEntry.description?.value, baseUrl)
    }

    private fun findThumbnail(mediaModule: MediaEntryModule, baseUrl: String?): String? {
        val groups = mediaModule.mediaGroups.orEmpty()
        val mediaContents = mediaModule.mediaContents.orEmpty().toList() +
            groups.flatMap { it.contents.orEmpty().toList() }
        val candidates =
            buildList {
                    add(mediaModule.metadata)
                    addAll(groups.map { mediaGroup -> mediaGroup.metadata })
                    addAll(mediaContents.map { content -> content.metadata })
                }
                .filterNotNull()
                .flatMap { it.thumbnail.orEmpty().toList() }

        candidates.forEach { thumbnail ->
            normalizeImageUrl(thumbnail.url?.toString(), baseUrl)?.let { return it }
        }
        mediaContents.forEach { content ->
            if (content.medium == "image" || content.type?.startsWith("image/", ignoreCase = true) == true) {
                normalizeImageUrl((content.reference as? UrlReference)?.url?.toString(), baseUrl)?.let { return it }
            }
        }
        return null
    }

    fun findThumbnail(text: String?, baseUrl: String? = null): String? = findHtmlThumbnail(text, baseUrl)

    suspend fun queryRssIconLink(feedLink: String?): String? {
        if (feedLink.isNullOrEmpty()) return null
        val iconFinder = BestIconFinder(okHttpClient)
        val domain = feedLink.extractDomain()
        return iconFinder.findBestIcon(domain ?: feedLink).also {
            Log.i("RLog", "queryRssIconByLink: get $it from $domain")
        }
    }

    suspend fun saveRssIcon(feedDao: FeedDao, feed: Feed, iconLink: String) {
        feedDao.update(feed.copy(icon = iconLink))
    }

    private suspend fun response(client: OkHttpClient, url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).build()).executeAsync()
}

internal fun BufferedSource.readStringLimited(
    charset: Charset,
    maxBytes: Long = FULL_CONTENT_MAX_BYTES,
): String {
    val buffer = Buffer()
    var readBytes = 0L

    while (true) {
        val bytesToRead = minOf(READ_BUFFER_BYTES, maxBytes + 1L - readBytes)
        val bytesRead = read(buffer, bytesToRead)
        if (bytesRead == -1L) break

        readBytes += bytesRead
        if (readBytes > maxBytes) {
            throw IOException("Response body exceeds ${maxBytes / 1024L} KiB")
        }
    }

    return buffer.readString(charset)
}

internal fun BufferedSource.readByteArrayLimited(maxBytes: Long): ByteArray {
    val buffer = Buffer()
    var readBytes = 0L

    while (true) {
        val bytesToRead = minOf(READ_BUFFER_BYTES, maxBytes + 1L - readBytes)
        val bytesRead = read(buffer, bytesToRead)
        if (bytesRead == -1L) break

        readBytes += bytesRead
        if (readBytes > maxBytes) {
            throw IOException("Response body exceeds ${maxBytes / 1024L} KiB")
        }
    }

    return buffer.readByteArray()
}

private fun BufferedSource.peekCharsetFromMeta(baseUrl: String): Charset {
    val peek = peek()
    val buffer = Buffer()

    while (buffer.size < FULL_CONTENT_CHARSET_SNIFF_BYTES) {
        val bytesRead = peek.read(buffer, FULL_CONTENT_CHARSET_SNIFF_BYTES - buffer.size)
        if (bytesRead == -1L) break
    }

    val peekContent = buffer.readString(Charsets.UTF_8)

    return runCatching {
        val element =
            Jsoup.parse(peekContent, baseUrl)
                .selectFirst("meta[http-equiv=content-type], meta[charset]")
        when {
            element == null -> Charsets.UTF_8
            element.hasAttr("charset") -> Charset.forName(element.attr("charset"))
            else ->
                element
                    .attr("content")
                    .substringAfter("charset=")
                    .substringBefore(";")
                    .removeSurrounding("\"")
                    .trim()
                    .lowercase()
                    .let { Charset.forName(it) }
        }
    }.getOrDefault(Charsets.UTF_8)
}
