package me.ash.reader.infrastructure.rss

import android.content.Context
import androidx.annotation.CheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFullContentRequest
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.di.IODispatcher

/** Disk cache retains the original account/id paths for existing offline articles. */
class ReaderCacheHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val rssHelper: RssHelper,
    private val accountService: AccountService,
) {
    private val cacheDir = context.cacheDir.resolve("readability")
    // Fixed-size lock striping prevents duplicate fetches without retaining article IDs.
    private val fetchLocks = Array(16) { Mutex() }

    private fun cacheFile(articleId: String, accountId: Int): File =
        cacheDir.resolve(accountId.toString()).resolve(readerCacheFileName(articleId))

    private fun preservePreviousVersion(content: String, file: File) {
        if (!file.isFile) return
        val previous = file.readText()
        if (previous == content) return
        val versions = file.parentFile!!.resolve(file.nameWithoutExtension + "-versions")
        val archive = versions.resolve(readerCacheFileName(previous))
        if (!archive.isFile) {
            check((versions.listFiles()?.size ?: 0) < 5) { "Five earlier source versions are retained. The existing source is unchanged." }
            writeContentToCache(previous, archive)
        }
    }

    private fun writeContentToCache(content: String, file: File) {
        file.parentFile!!.mkdirs()
        val temporary = File.createTempFile("article-", ".tmp", file.parentFile)
        try {
            temporary.writeText(content)
            check(temporary.renameTo(file)) { "Could not commit reader cache" }
        } finally {
            temporary.delete()
        }
    }

    @CheckResult
    suspend fun readFullContent(
        articleId: String,
        accountId: Int = accountService.getCurrentAccountId(),
    ): Result<String> = withContext(ioDispatcher) {
        readerCacheResult {
            val file = cacheFile(articleId, accountId)
            if (!file.isFile) throw FileNotFoundException()
            file.readText().also { check(it.isNotBlank()) { "Empty reader cache" } }
        }
    }

    @CheckResult
    suspend fun readOrFetchFullContent(article: Article): Result<String> =
        readOrFetchFullContent(ArticleFullContentRequest(article.accountId, article.id, article.title, article.link))

    /** Explicit user retry; a failed refresh never destroys an existing offline cache. */
    suspend fun refreshFullContent(article: Article): Result<String> =
        readOrFetchFullContent(ArticleFullContentRequest(article.accountId, article.id, article.title, article.link), forceRefresh = true)

    private suspend fun readOrFetchFullContent(article: ArticleFullContentRequest, forceRefresh: Boolean = false): Result<String> =
        withContext(ioDispatcher) {
            val key = "${article.accountId}:${article.id}"
            fetchLocks[(key.hashCode() and Int.MAX_VALUE) % fetchLocks.size].withLock {
                val cached = readFullContent(article.id, article.accountId)
                if (cached.isSuccess && !forceRefresh) return@withLock cached
                readerCacheResult {
                    val fetched = rssHelper.parseFullContent(article.link, article.title)
                    check(fetched.isNotBlank()) { "Empty article content" }
                    // Explicit refresh must not replace a useful offline extraction with a
                    // shorter/error-page result either. The original feed remains separate.
                    val fullContent = cached.getOrNull()?.let { previous ->
                        ArticleContentSelection.select(previous, fetched).html
                    } ?: fetched
                    val file = cacheFile(article.id, article.accountId)
                    // Retain the previous exact body before a modern extraction replaces it.
                    // If that cannot be retained, keep the current version rather than orphan it.
                    preservePreviousVersion(fullContent, file)
                    // A failed cache write must not prevent reading fetched content.
                    readerCacheResult { writeContentToCache(fullContent, file) }
                    fullContent
                }
            }
        }

    // Explicit user imports are an alternate source, not a replacement for publisher/feed HTML.
    // Keep them outside disposable extraction cache; no clipboard or browser is read here.
    private fun browserTextFile(articleId: String, accountId: Int): File =
        context.filesDir.resolve("reader-browser-text").resolve(accountId.toString())
            .resolve(readerCacheFileName(articleId))

    suspend fun readBrowserText(articleId: String, accountId: Int): Result<String> = withContext(ioDispatcher) {
        readerCacheResult {
            val file = browserTextFile(articleId, accountId)
            if (!file.isFile || file.length() > 1024L * 1024L) throw FileNotFoundException()
            file.readText().also { check(it.isNotBlank()) { "Empty browser text" } }
        }
    }

    suspend fun readPreviousContent(articleId: String, accountId: Int, browserText: Boolean): Result<String> =
        readEarlierContent(articleId, accountId, browserText, null)

    /** Repeated explicit Earlier choices cycle through every retained source, newest first. */
    suspend fun readEarlierContent(articleId: String, accountId: Int, browserText: Boolean, currentBody: String?): Result<String> =
        withContext(ioDispatcher) {
            readerCacheResult {
                val file = if (browserText) browserTextFile(articleId, accountId) else cacheFile(articleId, accountId)
                val versions = file.parentFile!!.resolve(file.nameWithoutExtension + "-versions")
                val earlier = versions.listFiles()?.filter { it.isFile && it.extension == "html" }
                    ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name }).orEmpty()
                if (earlier.isEmpty()) throw FileNotFoundException()
                val index = currentBody?.let { body -> earlier.indexOfFirst { it.name == readerCacheFileName(body) } } ?: -1
                val previous = earlier[(index + 1) % earlier.size]
                check(previous.length() <= 4L * 1024L * 1024L) { "Earlier source exceeds limit" }
                previous.readText().also { check(it.isNotBlank()) }
            }
        }

    suspend fun importBrowserText(articleId: String, accountId: Int, text: String): Result<String> =
        withContext(ioDispatcher) {
            val key = "${accountId}:${articleId}"
            fetchLocks[(key.hashCode() and Int.MAX_VALUE) % fetchLocks.size].withLock {
            readerCacheResult {
                val html = BrowserTextImport.toSafeHtml(text)
                currentCoroutineContext().ensureActive()
                val file = browserTextFile(articleId, accountId)
                // Retain earlier imported bodies for annotation provenance; never purge or
                // replace the publisher cache when the user supplies a different text version.
                preservePreviousVersion(html, file)
                writeContentToCache(html, file)
                html
            }
            }
        }

    suspend fun checkOrFetchFullContent(article: ArticleFullContentRequest): Boolean =
        readOrFetchFullContent(article).isSuccess

    suspend fun deleteCacheFor(
        articleId: String,
        accountId: Int = accountService.getCurrentAccountId(),
    ): Boolean = withContext(ioDispatcher) {
        readerCacheResult { cacheFile(articleId, accountId).delete() }.getOrDefault(false)
    }

    suspend fun clearCache(): Boolean {
        val accountId = accountService.getCurrentAccountId()
        return withContext(ioDispatcher) {
            readerCacheResult { cacheDir.resolve(accountId.toString()).deleteRecursively() }.getOrDefault(false)
        }
    }
}

internal fun readerCacheFileName(articleId: String): String =
    MessageDigest.getInstance("SHA-256").digest(articleId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".html"

internal inline fun <T> readerCacheResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
