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

    private suspend fun readOrFetchFullContent(article: ArticleFullContentRequest): Result<String> =
        withContext(ioDispatcher) {
            val key = "${article.accountId}:${article.id}"
            fetchLocks[(key.hashCode() and Int.MAX_VALUE) % fetchLocks.size].withLock {
                val cached = readFullContent(article.id, article.accountId)
                if (cached.isSuccess) return@withLock cached
                readerCacheResult {
                    val fullContent = rssHelper.parseFullContent(article.link, article.title)
                    check(fullContent.isNotBlank()) { "Empty article content" }
                    // A failed cache write must not prevent reading fetched content.
                    readerCacheResult { writeContentToCache(fullContent, cacheFile(article.id, article.accountId)) }
                    fullContent
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
